package io.github.kuscher.studiosnap.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import io.github.kuscher.studiosnap.service.SnapService
import java.nio.ByteBuffer

/**
 * MediaMuxer that waits for every expected track (video, and audio when on) before it starts: the
 * muxer can't add tracks after start(). Samples that arrive earlier are copied and written once it
 * starts. A track whose encoder dies before producing a format is [abandon]ed so the video still
 * saves, and a slow audio track is left out rather than holding the video back. Video is the
 * required track: it can legitimately be late (a sleeping or unchanging screen sends no frames),
 * so while it's missing, early audio is dropped instead.
 * Muxer errors (a full disk, say) are contained here: the file is marked failed instead of the
 * exception escaping into an encoder thread. Thread-safe: the video drain thread and the audio
 * thread both write here.
 */
class Mp4Writer(path: String, private var expectedTracks: Int) {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var added = 0
    private var started = false
    private var finished = false
    private var failed = false
    private var requiredAdded = false
    private val samples = HashMap<Int, Int>()
    private val pending = ArrayList<Pair<Int, Pair<ByteBuffer, MediaCodec.BufferInfo>>>()

    /**
     * The new track's index, or -1 if it can't join (the muxer already started without it).
     * [required] marks the track a recording can't do without (video).
     */
    @Synchronized
    fun addTrack(format: MediaFormat, required: Boolean = false): Int {
        if (started || finished || failed) {
            Log.w(SnapService.TAG, "muxer: late track left out (${format.getString(MediaFormat.KEY_MIME)})")
            return -1
        }
        val track = guard("addTrack") { muxer.addTrack(format) } ?: return -1
        added++
        if (required) requiredAdded = true
        maybeStart()
        return track
    }

    /** A track that will never deliver a format (its source failed): start without it. */
    @Synchronized
    fun abandon() {
        if (started) return
        expectedTracks--
        maybeStart()
    }

    private fun maybeStart() {
        if (started || finished || failed || added < expectedTracks || added == 0) return
        guard("start") { muxer.start() } ?: return
        started = true
        for ((track, s) in pending) write(track, s.first, s.second)
        pending.clear()
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (finished || failed || track < 0 || info.size <= 0) return
        if (started) {
            guard("write") {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                muxer.writeSampleData(track, buf, info)
            } ?: return
            samples[track] = (samples[track] ?: 0) + 1
            return
        }
        if (pending.size >= MAX_PENDING) {
            if (!requiredAdded) return // still waiting for video: drop this early sample
            // Video is flowing but another track (audio) is seconds late: start without it
            // rather than dropping video.
            Log.w(SnapService.TAG, "muxer: a track is late, starting with $added of $expectedTracks")
            expectedTracks = added
            maybeStart()
            if (started) write(track, buf, info)
            return
        }
        val src = buf.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
        val copy = ByteBuffer.allocate(info.size).put(src).apply { flip() }
        val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        pending.add(track to (copy to i))
    }

    /** Samples written for [track] so far. */
    @Synchronized
    fun sampleCount(track: Int): Int = samples[track] ?: 0

    /** Stops and releases the muxer. True only when it started, took samples and stopped cleanly. */
    @Synchronized
    fun finish(): Boolean {
        if (finished) return false
        finished = true
        var ok = started && !failed && samples.values.sum() > 0
        if (started) runCatching { muxer.stop() }.onFailure { ok = false; Log.w(SnapService.TAG, "muxer stop: $it") }
        runCatching { muxer.release() }
        pending.clear()
        return ok
    }

    private inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        failed = true
        Log.w(SnapService.TAG, "muxer $what failed: $e")
        null
    }

    private companion object {
        const val MAX_PENDING = 240
    }
}
