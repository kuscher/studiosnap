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
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import java.io.File
import java.io.FileOutputStream

/**
 * Records the screen to MP4 with MediaProjection -> MediaCodec (H.264) -> MediaMuxer, in a
 * foreground service of type mediaProjection. Video-only for now (mic/device audio, pause, region
 * crop and GIF export arrive next). Started by [RecordActivity] once consent is granted.
 */
class RecordService : Service(), RecordController {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var drainThread: HandlerThread? = null
    private lateinit var cacheFile: File

    @Volatile private var muxing = false
    @Volatile private var trackIndex = -1
    @Volatile private var stopRequested = false
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
        startForegroundNotification()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            startEncoder(code, data)
        } catch (e: Exception) {
            Log.e(SnapService.TAG, "record start failed: $e")
            cleanup(); stopSelf(); return START_NOT_STICKY
        }
        instance = this
        RecordingBus.controller = this
        RecordingBus.active = true
        startedAt = SystemClock.elapsedRealtime()
        ticker.post(tick)
        SnapService.instance?.onRecordingStarted()
        return START_NOT_STICKY
    }

    private fun startEncoder(code: Int, data: Intent) {
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
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c

        cacheFile = File(cacheDir, "rec-${System.currentTimeMillis()}.mp4")
        muxer = MediaMuxer(cacheFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val pm = getSystemService(MediaProjectionManager::class.java)
        val proj = pm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (!stopRequested) stop() }
        }, ticker)
        projection = proj
        virtualDisplay = proj.createVirtualDisplay(
            "studiosnap-rec", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )

        drainThread = HandlerThread("ss-rec-drain").apply { start() }
        Handler(drainThread!!.looper).post { drain() }
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer!!.addTrack(c.outputFormat); muxer!!.start(); muxing = true
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing && buf != null) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer!!.writeSampleData(trackIndex, buf, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        finishFile()
    }

    override fun stop() { requestStop(discard = false) }
    override fun discard() { requestStop(discard = true) }

    private fun requestStop(discard: Boolean) {
        if (stopRequested) return
        stopRequested = true; discarded = discard
        RecordingBus.active = false
        ticker.removeCallbacks(tick)
        try { codec?.signalEndOfInputStream() } catch (e: Exception) { finishFile() }
    }

    private var finished = false
    @Synchronized private fun finishFile() {
        if (finished) return
        finished = true
        val ok = muxing
        cleanup()
        val dur = RecordingBus.elapsedMs
        ticker.post {
            if (!discarded && ok && cacheFile.exists() && cacheFile.length() > 0) {
                val uri = saveToMovies(cacheFile)
                val thumb = uri?.let { runCatching { contentResolver.loadThumbnail(it, android.util.Size(600, 380), null) }.getOrNull() }
                SnapService.instance?.onRecordingSaved(true, dur, thumb)
            } else {
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
        runCatching { codec?.stop(); codec?.release() }
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { projection?.stop() }
        runCatching { inputSurface?.release() }
        runCatching { drainThread?.quitSafely() }
        virtualDisplay = null; codec = null; muxer = null; projection = null; inputSurface = null; drainThread = null
    }

    private fun saveToMovies(file: File): android.net.Uri? = try {
        val name = "Rec " + java.text.SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", java.util.Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StudioSnap")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
        }
        file.delete()
        Log.i(SnapService.TAG, "recording saved -> Movies/StudioSnap")
        uri
    } catch (e: Exception) { Log.w(SnapService.TAG, "save recording failed: $e"); null }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("StudioSnap is recording")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, n)
    }

    companion object {
        const val CHANNEL = "recording"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var instance: RecordService? = null
            private set

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, RecordService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }
    }
}
