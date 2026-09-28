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
 * saves. Thread-safe: the video drain thread and the audio thread both write here.
 */
class Mp4Writer(path: String, private var expectedTracks: Int) {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var added = 0
    private var started = false
    private var finished = false
    private var samples = 0
    private val pending = ArrayList<Pair<Int, Pair<ByteBuffer, MediaCodec.BufferInfo>>>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val track = muxer.addTrack(format)
        added++
        maybeStart()
        return track
    }

    /** A track that will never deliver a format (its source failed): start without it. */
    @Synchronized
    fun abandon() {
        expectedTracks--
        maybeStart()
    }

    private fun maybeStart() {
        if (started || finished || added < expectedTracks || added == 0) return
        muxer.start()
        started = true
        for ((track, s) in pending) write(track, s.first, s.second)
        pending.clear()
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (finished || info.size <= 0) return
        if (started) {
            buf.position(info.offset); buf.limit(info.offset + info.size)
            muxer.writeSampleData(track, buf, info)
            samples++
            return
        }
        if (pending.size >= MAX_PENDING) return
        val src = buf.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
        val copy = ByteBuffer.allocate(info.size).put(src).apply { flip() }
        val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        pending.add(track to (copy to i))
    }

    /** Stops and releases the muxer. True when the file holds at least one sample. */
    @Synchronized
    fun finish(): Boolean {
        if (finished) return false
        finished = true
        val ok = started && samples > 0
        if (started) runCatching { muxer.stop() }.onFailure { Log.w(SnapService.TAG, "muxer stop: $it") }
        runCatching { muxer.release() }
        pending.clear()
        return ok
    }

    private companion object {
        const val MAX_PENDING = 240
    }
}
