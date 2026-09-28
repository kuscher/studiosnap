package io.github.kuscher.studiosnap.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import io.github.kuscher.studiosnap.service.SnapService

/**
 * Scrolling ("full page") capture. Repeatedly grabs the scrollable region's own surface, scrolls it
 * forward, and stitches each new frame onto the growing image by matching the overlap between the
 * previous frame's bottom and the new frame's top.
 *
 * Capturing only the scroll node's rect (not the whole window) keeps sticky headers/footers — which
 * live outside it — out of the stitch, so the seams line up. Everything is async: each window
 * capture is a callback, and scrolls settle on a delay before the next grab.
 *
 * @param captureWindow grabs a window's own surface (cursor-free, no overlaps).
 * @param scrollForward performs one forward scroll on the target; returns false when it can't scroll.
 * @param regionLocal the scroll node's bounds in window-local pixels (what to keep from each frame).
 */
class ScrollCapture(
    private val main: Handler,
    private val winId: Int,
    private val regionLocal: Rect,
    private val captureWindow: (Int, (Bitmap?) -> Unit) -> Unit,
    private val scrollForward: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private val frames = ArrayList<Bitmap>()
    private val appendFrom = ArrayList<Int>()   // first row to append from each frame
    private var retries = 0

    fun run(done: (Bitmap?) -> Unit) {
        step(done)
    }

    private fun step(done: (Bitmap?) -> Unit) {
        captureWindow(winId) { full ->
            val strip = full?.let { crop(it) }
            if (strip == null) {
                if (retries++ < 2) { main.postDelayed({ step(done) }, SETTLE_MS); return@captureWindow }
                done(compose()); return@captureWindow
            }
            retries = 0
            if (frames.isEmpty()) {
                frames.add(strip); appendFrom.add(0)
            } else {
                val ov = overlap(frames.last(), strip)
                val newRows = strip.height - ov
                if (newRows < MIN_STEP) { log("end: newRows=$newRows (settled)"); done(compose()); return@captureWindow }
                frames.add(strip); appendFrom.add(ov)
                log("frame ${frames.size}: overlap=$ov new=$newRows")
            }
            if (frames.size >= MAX_FRAMES || totalHeight() >= MAX_HEIGHT) { log("end: cap"); done(compose()); return@captureWindow }
            if (!scrollForward()) { log("end: cannot scroll"); done(compose()); return@captureWindow }
            main.postDelayed({ step(done) }, SETTLE_MS)
        }
    }

    private fun crop(full: Bitmap): Bitmap? {
        val x = regionLocal.left.coerceIn(0, full.width - 1)
        val y = regionLocal.top.coerceIn(0, full.height - 1)
        val w = regionLocal.width().coerceIn(1, full.width - x)
        val h = regionLocal.height().coerceIn(1, full.height - y)
        return try { Bitmap.createBitmap(full, x, y, w, h) } catch (e: Exception) { null }
    }

    private fun totalHeight(): Int {
        var t = 0
        for (i in frames.indices) t += frames[i].height - appendFrom[i]
        return t
    }

    private fun compose(): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0]
        val w = frames[0].width
        val out = Bitmap.createBitmap(w, totalHeight(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        var y = 0
        for (i in frames.indices) {
            val f = frames[i]; val from = appendFrom[i]
            val src = Rect(0, from, f.width, f.height)
            val dst = Rect(0, y, w, y + (f.height - from))
            c.drawBitmap(f, src, dst, null)
            y += f.height - from
        }
        return out
    }

    // ---- overlap detection ----

    /** Rows of [prev]'s bottom that equal [cur]'s top (the vertical overlap between two frames). */
    private fun overlap(prev: Bitmap, cur: Bitmap): Int {
        val h = minOf(prev.height, cur.height)
        val sigPrev = signature(prev)
        val sigCur = signature(cur)
        var best = h; var bestCost = Double.MAX_VALUE
        var ov = MIN_OVERLAP
        while (ov <= h) {
            val cost = matchCost(sigPrev, sigCur, prev.height, ov)
            if (cost < bestCost) { bestCost = cost; best = ov }
            ov++
        }
        // No believable alignment (a full-viewport jump with no overlap): treat the whole frame as new.
        return if (bestCost > MATCH_THRESHOLD) 0 else best
    }

    /** Mean per-sample luma difference when [prev]'s last [ov] rows align with [cur]'s first [ov]. */
    private fun matchCost(sigPrev: IntArray, sigCur: IntArray, prevH: Int, ov: Int): Double {
        val start = prevH - ov
        val stepRows = maxOf(1, ov / SAMPLE_ROWS)
        var sum = 0L; var n = 0
        var i = 0
        while (i < ov) {
            val a = (start + i) * COLS
            val b = i * COLS
            var k = 0
            while (k < COLS) { sum += kotlin.math.abs(sigPrev[a + k] - sigCur[b + k]); k++ }
            n += COLS
            i += stepRows
        }
        return if (n == 0) Double.MAX_VALUE else sum.toDouble() / n
    }

    /** Per-row luma sampled at [COLS] evenly-spaced columns, row-major. */
    private fun signature(bmp: Bitmap): IntArray {
        val w = bmp.width; val h = bmp.height
        val sig = IntArray(h * COLS)
        val row = IntArray(w)
        val xs = IntArray(COLS) { ((it + 0.5f) / COLS * w).toInt().coerceIn(0, w - 1) }
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * COLS
            var k = 0
            while (k < COLS) {
                val p = row[xs[k]]
                sig[base + k] = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                k++
            }
            y++
        }
        return sig
    }

    companion object {
        private const val COLS = 24            // sample columns per row
        private const val SAMPLE_ROWS = 140    // cap of rows compared per overlap candidate
        private const val MIN_OVERLAP = 24     // ignore tiny (likely spurious) overlaps
        private const val MIN_STEP = 8         // fewer new rows than this ⇒ the view has settled
        private const val MATCH_THRESHOLD = 26.0 // mean luma diff above which no overlap is believed
        private const val MAX_FRAMES = 16
        private const val MAX_HEIGHT = 20000
        const val SETTLE_MS = 450L
    }
}
