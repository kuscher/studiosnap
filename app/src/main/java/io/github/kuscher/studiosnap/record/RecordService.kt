package io.github.kuscher.studiosnap.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.util.Settings
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records the screen to MP4 with MediaProjection -> MediaCodec (H.264) -> [Mp4Writer], in a
 * foreground service of type mediaProjection. When the Record-mode toggles ask for it, an
 * [AudioCapture] adds the microphone and/or system audio as an AAC track (the service then also
 * runs as type microphone). Pause, region crop and GIF export arrive later. Started by
 * [RecordActivity] once consent is granted.
 */
class RecordService : Service(), RecordController {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var writer: Mp4Writer? = null
    private var audio: AudioCapture? = null
    private var audioStarted = false
    private var inputSurface: Surface? = null
    private var drainThread: HandlerThread? = null
    private lateinit var cacheFile: File

    @Volatile private var videoTrack = -1
    /** Tracks (video, audio) still running; the file is finished when this reaches zero. */
    private val tracksLeft = AtomicInteger(1)
    @Volatile private var stopRequested = false
    @Volatile private var stopAt = 0L
    @Volatile private var stopPtsUs = Long.MAX_VALUE
    private var fgMicrophone = false
    @Volatile private var discarded = false
    private var startedAt = 0L
    private val ticker = Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!stopRequested) { RecordingBus.elapsedMs = SystemClock.elapsedRealtime() - startedAt; ticker.postDelayed(this, 250) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent.action == ACTION_STOP) {
            if (instance != null) stop() else stopSelf()
            return START_NOT_STICKY
        }
        if (instance != null) {
            // Already recording (or still saving): never let a second start replace the session
            // under its running encoder threads. Still satisfy the startForeground contract.
            Log.w(SnapService.TAG, "record start ignored: a recording is already running")
            startForegroundNotification(microphone = fgMicrophone)
            return START_NOT_STICKY
        }
        val settings = Settings(this)
        // Only claim the microphone FGS type when audio is on AND permitted: asking for the type
        // without RECORD_AUDIO throws, which would kill the whole recording.
        val wantAudio = (settings.recMic || settings.recSystemAudio) && AudioCapture.hasPermission(this)
        fgMicrophone = wantAudio
        startForegroundNotification(microphone = wantAudio)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            startEncoder(code, data, wantAudio, settings)
        } catch (e: Exception) {
            Log.e(SnapService.TAG, "record start failed: $e")
            cleanup(); stopSelf()
            // Cleared here too: the accessibility service may be between objects right now (it's
            // re-bound around the consent dialog), and a stuck flag keeps the camera bubble up.
            SnapService.recordPending = false
            SnapService.instance?.onRecordingSaved(false, 0L, null)
            return START_NOT_STICKY
        }
        instance = this
        RecordingBus.controller = this
        RecordingBus.active = true
        startedAt = SystemClock.elapsedRealtime()
        ticker.post(tick)
        SnapService.recordPending = false // even with no service object to tell (see above)
        SnapService.instance?.onRecordingStarted()
        return START_NOT_STICKY
    }

    private fun startEncoder(code: Int, data: Intent, wantAudio: Boolean, settings: Settings) {
        val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        var w = metrics.width(); var h = metrics.height()
        // encoders want even dimensions
        w = w and 1.inv(); h = h and 1.inv()
        val dpi = resources.displayMetrics.densityDpi

        val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 4.5).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType("video/avc")
        codec = c // held before configure, so cleanup() releases it if configure/start throws
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()

        cacheFile = File(cacheDir, "rec-${System.currentTimeMillis()}.mp4")

        val pm = getSystemService(MediaProjectionManager::class.java)
        val proj = pm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (!stopRequested) stop() }
        }, ticker)
        projection = proj

        // Audio needs the projection (system audio rides on it), and the writer needs to know
        // how many tracks to wait for, so both are settled before the first frame arrives.
        val a = if (wantAudio) AudioCapture.create(this, proj, settings.recMic, settings.recSystemAudio, ::trackDone) else null
        audio = a
        val tracks = if (a != null) 2 else 1
        tracksLeft.set(tracks)
        // A file that can't be written (a full disk, say) ends the recording now, not at Stop.
        val wr = Mp4Writer(cacheFile.absolutePath, tracks) {
            ticker.post {
                if (!stopRequested) {
                    android.widget.Toast.makeText(this, "Recording stopped: couldn't write the file", android.widget.Toast.LENGTH_LONG).show()
                    stop()
                }
            }
        }
        writer = wr

        virtualDisplay = proj.createVirtualDisplay(
            "studiosnap-rec", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )

        drainThread = HandlerThread("ss-rec-drain").apply { start() }
        Handler(drainThread!!.looper).post { drain() }
        a?.start(wr)
        audioStarted = a != null
    }

    private fun drain() {
        val c = codec
        val w = writer
        try {
            if (c == null || w == null) return
            val info = MediaCodec.BufferInfo()
            while (true) {
                // After Stop, an encoder that never emits end-of-stream (and gets no new frame to
                // push it out) must not keep the file open forever, whatever else it returns.
                if (stopRequested && SystemClock.elapsedRealtime() - stopAt > EOS_DEADLINE_MS) {
                    Log.w(SnapService.TAG, "video encoder gave no end-of-stream; finishing anyway")
                    break
                }
                val idx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> videoTrack = w.addTrack(c.outputFormat, required = true)
                    idx >= 0 -> {
                        // Some encoders only emit end-of-stream after one more frame arrives, so a
                        // frame from after Stop was pressed marks the end: it isn't recorded.
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val afterStop = !config && stopRequested && info.presentationTimeUs > stopPtsUs && info.size > 0
                        try {
                            val buf = c.getOutputBuffer(idx)
                            if (config) info.size = 0
                            if (!afterStop && info.size > 0 && buf != null) w.write(videoTrack, buf, info)
                        } finally {
                            c.releaseOutputBuffer(idx, false)
                        }
                        if (afterStop || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "video drain ended: $e")
        } finally {
            if (videoTrack < 0) runCatching { writer?.abandon() }
            // Video ending before the user stopped (a codec error) ends the whole session, so the
            // audio track doesn't record on alone.
            if (!stopRequested) ticker.post { stop() }
            trackDone()
        }
    }

    private fun trackDone() { if (tracksLeft.decrementAndGet() == 0) finishFile() }

    override fun stop() { requestStop(discard = false) }
    override fun discard() { requestStop(discard = true) }

    private fun requestStop(discard: Boolean) {
        if (stopRequested) return
        // Everything the drain thread reads is set before the flag it checks (it reads the flag
        // first, then these).
        discarded = discard
        stopAt = SystemClock.elapsedRealtime()
        stopPtsUs = System.nanoTime() / 1000 // screen frames are stamped in this (monotonic) clock
        stopRequested = true
        RecordingBus.active = false
        ticker.removeCallbacks(tick)
        audio?.stop()
        // If the encoder can't take end-of-stream, stopping it makes the drain loop exit instead.
        try { codec?.signalEndOfInputStream() } catch (e: Exception) { runCatching { codec?.stop() } }
    }

    private var finished = false
    @Synchronized private fun finishFile() {
        if (finished) return
        finished = true
        val w = writer
        val ok = w != null && w.finish() && videoTrack >= 0 && w.sampleCount(videoTrack) > 0
        cleanup()
        val dur = RecordingBus.elapsedMs
        ticker.post {
            val uri = if (!discarded && ok && cacheFile.exists() && cacheFile.length() > 0) saveToMovies(cacheFile) else null
            if (uri != null) {
                val thumb = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(600, 380), null) }.getOrNull()
                SnapService.instance?.onRecordingSaved(true, dur, thumb)
            } else {
                if (!discarded) android.widget.Toast.makeText(this, "Couldn't save the recording", android.widget.Toast.LENGTH_LONG).show()
                cacheFile.delete()
                SnapService.instance?.onRecordingSaved(false, 0L, null)
            }
            RecordingBus.controller = null
            instance = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { writer?.finish() }
        if (!audioStarted) runCatching { audio?.release() }
        runCatching { projection?.stop() }
        runCatching { inputSurface?.release() }
        runCatching { drainThread?.quitSafely() }
        virtualDisplay = null; codec = null; writer = null; audio = null; projection = null; inputSurface = null; drainThread = null
    }

    /** Copies the finished MP4 into Movies/StudioSnap. Null (and no half-written row) on failure. */
    private fun saveToMovies(file: File): android.net.Uri? {
        val name = "Rec " + java.text.SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", java.util.Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StudioSnap")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        var uri: android.net.Uri? = null
        return try {
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("insert returned null")
            val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("no output stream")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IllegalStateException("publish updated no row")
            Log.i(SnapService.TAG, "recording saved -> Movies/StudioSnap")
            uri
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "save recording failed: $e")
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        } finally {
            file.delete()
        }
    }

    private fun startForegroundNotification(microphone: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        // A Stop action here too, so a recording can always be ended even if the pill is gone.
        val stopIntent = android.app.PendingIntent.getService(
            this, 0, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("StudioSnap is recording")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true).build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, n, types)
    }

    companion object {
        const val CHANNEL = "recording"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "io.github.kuscher.studiosnap.record.STOP"
        private const val EOS_DEADLINE_MS = 1500L
        @Volatile var instance: RecordService? = null
            private set

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, RecordService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }
    }
}
