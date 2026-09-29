package io.github.kuscher.studiosnap.util

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import android.util.Log
import io.github.kuscher.studiosnap.service.SnapService
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * adb-only checks for recorded audio that never look at (or play back) the content: the track
 * list of the newest recording, plus the audio's loudness and dominant pitch, and a test tone
 * for exercising system-audio capture. Driven from [DebugReceiver].
 */
object RecProbe {

    private const val LOUD = 1000

    /** Logs the newest StudioSnap recording's tracks, A/V start offset, audio RMS and pitch. */
    fun inspectLatest(ctx: Context) {
        val uri = latestVideo(ctx) ?: run { Log.w(SnapService.TAG, "recinfo: no video"); return }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var audioIdx = -1
            val firstPts = HashMap<String, Long>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
                val durMs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else -1
                val extra = if (mime.startsWith("audio/")) {
                    audioIdx = i
                    "rate=${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} ch=${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}"
                } else "${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}"
                ex.selectTrack(i)
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                firstPts[mime.substringBefore('/')] = ex.sampleTime
                ex.unselectTrack(i)
                Log.i(SnapService.TAG, "recinfo track $i $mime $extra dur=${durMs}ms")
            }
            val v = firstPts["video"]; val a = firstPts["audio"]
            if (v != null && a != null) Log.i(SnapService.TAG, "recinfo start offset audio-video=${(a - v) / 1000}ms")
            if (audioIdx < 0) { Log.i(SnapService.TAG, "recinfo: no audio track"); return }
            decodeStats(ex, audioIdx)
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "recinfo failed: $e")
        } finally {
            ex.release()
        }
    }

    private fun decodeStats(ex: MediaExtractor, idx: Int) {
        val fmt = ex.getTrackFormat(idx)
        ex.selectTrack(idx)
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        val info = MediaCodec.BufferInfo()
        var sumSq = 0.0; var n = 0L; var crossings = 0L; var prev = 0; var peak = 0; var loud = 0L
        // Pitch from zero crossings in loud 10 ms blocks only, so silence doesn't dilute it.
        val block = rate / 100
        var bSq = 0.0; var bCross = 0; var bLen = 0
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = dec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                    if (size < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = dec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val sb = dec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                var k = 0
                while (sb.hasRemaining()) {
                    val s = sb.get().toInt()
                    if (k % ch == 0) { // left channel only
                        sumSq += s.toDouble() * s; n++
                        bSq += s.toDouble() * s; bLen++
                        if ((s >= 0) != (prev >= 0)) bCross++
                        prev = s
                        if (bLen == block) {
                            if (sqrt(bSq / bLen) > LOUD) { loud += bLen; crossings += bCross }
                            bSq = 0.0; bCross = 0; bLen = 0
                        }
                        if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
                    }
                    k++
                }
                dec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        dec.stop(); dec.release()
        val rms = if (n > 0) sqrt(sumSq / n) else 0.0
        val hz = if (loud > 0) crossings * rate / (2.0 * loud) else 0.0
        Log.i(SnapService.TAG, "recinfo audio ${n * 1000 / rate}ms rms=${"%.0f".format(rms)} peak=$peak loud=${loud * 1000 / rate}ms approxHz=${"%.0f".format(hz)}")
    }

    /**
     * Logs the mean color of a small patch (display px) in the newest recording's frame at 1 s.
     * Checks that an overlay (the camera bubble's test pattern) made it into the video, without
     * the frame ever leaving the device.
     */
    fun patchColor(ctx: Context, x: Int, y: Int, size: Int = 24) {
        val uri = latestVideo(ctx) ?: run { Log.w(SnapService.TAG, "recpixel: no video"); return }
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(1_000_000) ?: run { Log.w(SnapService.TAG, "recpixel: no frame"); return }
            var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
            for (py in (y - size / 2).coerceAtLeast(0) until (y + size / 2).coerceAtMost(f.height)) {
                for (px in (x - size / 2).coerceAtLeast(0) until (x + size / 2).coerceAtMost(f.width)) {
                    val c = f.getPixel(px, py)
                    rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF; n++
                }
            }
            f.recycle()
            if (n > 0) Log.i(SnapService.TAG, "recpixel ($x,$y) mean rgb=(${rs / n},${gs / n},${bs / n})")
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "recpixel failed: $e")
        } finally {
            r.release()
        }
    }

    /** Plays a sine tone as media for [seconds], so system-audio capture has something to hear. */
    fun tone(seconds: Int, hz: Int) {
        Thread {
            val rate = 48_000
            val frames = rate * seconds
            val pcm = ShortArray(frames) { (sin(2 * PI * hz * it / rate) * 8000).toInt().toShort() }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            track.write(pcm, 0, frames)
            track.play()
            Log.i(SnapService.TAG, "tone ${hz}Hz for ${seconds}s")
            Thread.sleep(seconds * 1000L + 200)
            track.release()
        }.start()
    }

    private fun latestVideo(ctx: Context): android.net.Uri? {
        val proj = arrayOf(MediaStore.Video.Media._ID)
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%StudioSnap%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cur ->
            if (cur.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cur.getLong(0))
        }
        return null
    }
}
