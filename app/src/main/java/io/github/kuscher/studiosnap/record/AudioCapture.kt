package io.github.kuscher.studiosnap.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.projection.MediaProjection
import android.util.Log
import io.github.kuscher.studiosnap.service.SnapService
import java.nio.ByteOrder

/**
 * Records the microphone and/or the sound apps play (AudioPlaybackCapture, which rides on the
 * screen-recording MediaProjection), mixes them to 48 kHz stereo, encodes AAC and writes it to the
 * [Mp4Writer] as the recording's audio track.
 *
 * Timestamps come from AudioRecord.getTimestamp in the monotonic clock, the same clock the screen
 * frames use, so the voice stays in sync with the video. With both sources on, the microphone is
 * the clock and system audio is pulled from a small buffer (padded with silence when apps are
 * quiet, trimmed if it runs ahead).
 */
class AudioCapture private constructor(
    private val mic: AudioRecord?,
    private val system: AudioRecord?,
    private val codec: MediaCodec,
    private val onDone: () -> Unit,
) {
    private lateinit var writer: Mp4Writer
    @Volatile private var stopping = false
    private var track = -1
    private var lastPtsUs = -1L
    private val echo: AcousticEchoCanceler? =
        if (mic != null && system != null && AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(mic!!.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null

    /**
     * Starts capturing into [writer]. If the encoder or a source refuses to start, the audio
     * track is dropped (the recording saves video only) and [onDone] still fires once.
     */
    fun start(writer: Mp4Writer) {
        this.writer = writer
        try {
            codec.start()
            mic?.startRecording()
            system?.startRecording()
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "audio start failed, recording video only: $e")
            runCatching { codec.stop() }
            release()
            writer.abandon()
            onDone()
            return
        }
        Thread(::run, "ss-rec-audio").start()
    }

    /** Frees everything when recording fails before [start]. */
    fun release() {
        runCatching { echo?.release() }
        runCatching { mic?.release() }
        runCatching { system?.release() }
        runCatching { codec.release() }
    }

    /** Ends the track: the thread sends end-of-stream, drains the encoder and calls onDone. */
    fun stop() {
        stopping = true
        // Unblock a read that is waiting for sound (system audio can stall while apps are silent).
        runCatching { mic?.stop() }
        runCatching { system?.stop() }
    }

    private fun run() {
        val ring = if (mic != null && system != null) ShortRing(RATE * CHANNELS / 2) else null
        val feeder = ring?.let { r -> Thread({ feed(system!!, r) }, "ss-rec-sysaudio").apply { start() } }
        val out = ShortArray(FRAMES * CHANNELS)
        val micBuf = ShortArray(FRAMES)
        val sysBuf = ShortArray(FRAMES * CHANNELS)
        var frames = 0L
        try {
            while (!stopping) {
                val n: Int
                if (mic != null) {
                    n = mic.read(micBuf, 0, FRAMES, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { if (stopping) break; Log.w(SnapService.TAG, "mic read $n"); break }
                    if (ring != null) ring.take(sysBuf, n * CHANNELS) else sysBuf.fill(0, 0, n * CHANNELS)
                    for (i in 0 until n) {
                        val m = micBuf[i].toInt()
                        out[2 * i] = clamp(m + sysBuf[2 * i])
                        out[2 * i + 1] = clamp(m + sysBuf[2 * i + 1])
                    }
                } else {
                    val got = system!!.read(out, 0, FRAMES * CHANNELS, AudioRecord.READ_BLOCKING)
                    if (got <= 0) { if (stopping) break; Log.w(SnapService.TAG, "system audio read $got"); break }
                    n = got / CHANNELS
                }
                queue(out, n, ptsUs(mic ?: system!!, frames, n), eos = false)
                frames += n
                drain(eos = false)
            }
            // End-of-stream must reach the encoder (unlike a PCM chunk, it can't be dropped), so
            // keep draining and retrying for up to a second while the encoder is backed up.
            var sent = false
            for (attempt in 0 until 10) {
                if (queue(out, 0, lastPtsUs + 1, eos = true)) { sent = true; break }
            }
            if (!sent) Log.w(SnapService.TAG, "audio end-of-stream not accepted; the last few ms may be cut")
            drain(eos = true)
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "audio capture failed: $e")
        } finally {
            stopping = true
            runCatching { system?.stop() }
            feeder?.join(500)
            if (track < 0) writer.abandon()
            runCatching { echo?.release() }
            runCatching { mic?.release() }
            runCatching { system?.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            Log.i(SnapService.TAG, "audio track done: ${frames * 1000 / RATE} ms")
            onDone()
        }
    }

    /** Second-source reader (system audio while the mic is the clock). */
    private fun feed(rec: AudioRecord, ring: ShortRing) {
        val buf = ShortArray(FRAMES * CHANNELS)
        while (!stopping) {
            val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) break
            ring.put(buf, n)
        }
    }

    /** Presentation time of the first frame of this chunk, in the monotonic clock (µs). */
    private fun ptsUs(rec: AudioRecord, framesBefore: Long, n: Int): Long {
        val ts = AudioTimestamp()
        val pts = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            (ts.nanoTime + (framesBefore - ts.framePosition) * 1_000_000_000L / RATE) / 1000
        } else {
            System.nanoTime() / 1000 - n * 1_000_000L / RATE
        }
        return if (pts <= lastPtsUs) lastPtsUs + 1 else pts
    }

    /** Hands a chunk (or end-of-stream) to the encoder; false if it had no free input buffer. */
    private fun queue(pcm: ShortArray, frames: Int, ptsUs: Long, eos: Boolean): Boolean {
        var idx = -1
        for (attempt in 0 until 10) {
            idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) break
            drain(eos = false)
        }
        if (idx < 0) {
            if (!eos) Log.w(SnapService.TAG, "audio encoder busy, chunk dropped")
            return false
        }
        val buf = codec.getInputBuffer(idx)!!
        buf.clear()
        val shorts = frames * CHANNELS
        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, shorts)
        codec.queueInputBuffer(idx, 0, shorts * 2, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        lastPtsUs = ptsUs
        return true
    }

    private fun drain(eos: Boolean) {
        val info = MediaCodec.BufferInfo()
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                // At end-of-stream, wait up to ~1 s for the encoder's last buffers, never forever.
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos || ++waits > 100) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = writer.addTrack(codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && track >= 0) writer.write(track, buf, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Fixed-size FIFO of interleaved samples between the system-audio reader and the mixer. */
    private class ShortRing(private val capacity: Int) {
        private val data = ShortArray(capacity)
        private var head = 0
        private var size = 0

        @Synchronized
        fun put(src: ShortArray, n: Int) {
            for (i in 0 until n) {
                data[(head + size) % capacity] = src[i]
                if (size < capacity) size++ else head = (head + 1) % capacity
            }
            // Keep at most ~100 ms queued so system audio can't drift behind the mic.
            val max = RATE * CHANNELS / 10
            if (size > max) { head = (head + size - max) % capacity; size = max }
        }

        /** Fills [dst] with [n] samples, padding with silence when not enough have arrived. */
        @Synchronized
        fun take(dst: ShortArray, n: Int) {
            val avail = minOf(n, size)
            for (i in 0 until avail) dst[i] = data[(head + i) % capacity]
            for (i in avail until n) dst[i] = 0
            head = (head + avail) % capacity
            size -= avail
        }
    }

    companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        private const val FRAMES = 1024

        private fun clamp(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        fun hasPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /**
         * Builds the capture for the requested sources, or null when none can record (no
         * permission, or AudioRecord refused). A source that fails is dropped, not fatal.
         */
        @SuppressLint("MissingPermission")
        fun create(
            ctx: Context,
            projection: MediaProjection,
            wantMic: Boolean,
            wantSystem: Boolean,
            onDone: () -> Unit,
        ): AudioCapture? {
            if ((!wantMic && !wantSystem) || !hasPermission(ctx)) return null
            val mic = if (wantMic) build("mic") {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                    .setBufferSizeInBytes(bufferBytes(AudioFormat.CHANNEL_IN_MONO))
                    .build()
            } else null
            val system = if (wantSystem) build("system audio") {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format(AudioFormat.CHANNEL_IN_STEREO))
                    .setBufferSizeInBytes(bufferBytes(AudioFormat.CHANNEL_IN_STEREO))
                    .build()
            } else null
            if (mic == null && system == null) return null
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES * CHANNELS * 2 * 2)
                }
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(SnapService.TAG, "AAC encoder unavailable: $e")
                runCatching { codec?.release() }
                mic?.release(); system?.release()
                return null
            }
            Log.i(SnapService.TAG, "audio: mic=${mic != null} system=${system != null}")
            return AudioCapture(mic, system, codec, onDone)
        }

        private fun format(mask: Int) = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(mask)
            .build()

        private fun bufferBytes(mask: Int): Int {
            val min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
            return maxOf(min * 2, FRAMES * CHANNELS * 2 * 4)
        }

        private fun build(what: String, make: () -> AudioRecord): AudioRecord? = try {
            make().takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: run { Log.w(SnapService.TAG, "$what: AudioRecord not initialized"); null }
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "$what unavailable: $e"); null
        }
    }
}
