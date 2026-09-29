package io.github.kuscher.studiosnap.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.github.kuscher.studiosnap.service.SnapService
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns camera frames into a cut-out of the person: Google's selfie segmentation model
 * (MediaPipe "selfie_segmenter", square 256x256, Apache 2.0, bundled in assets/models) runs through
 * LiteRT and gives each pixel a person probability; the rest is made transparent. (The fast
 * variant: ~6 ms a frame on an x86 Googlebook CPU, so the mask keeps up with the video.) The camera bubble
 * shows the result, so a recording gets a floating head and body with no background.
 *
 * Runs as a CameraX analyzer on its own thread; [onFrame] gets each finished ARGB frame. Frames are
 * rotated upright, mirrored (like a mirror, matching the normal bubble) and center-cropped to a
 * square (the bubble is square, and so is the model's input, so nothing is stretched). The model
 * takes a 256x256 RGB image in [0, 1] and returns one person probability per pixel.
 * The mask is smoothed over time, softened at the edge and scaled back up (bilinear) onto the frame.
 *
 * The model is much slower than the camera (about 120 ms a frame on an x86 Googlebook CPU), so it
 * runs on its own thread on the newest frame whenever it's free, and every camera frame is shown
 * with the latest mask: the picture stays smooth, only the cut-out's edge updates less often.
 */
class Cutout(
    private val ctx: Context,
    private val mirror: Boolean,
    private val onFrame: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {
    private var interpreter: Interpreter? = null
    private val interpreterLock = Any()
    private val input = ByteBuffer.allocateDirect(4 * IN_W * IN_H * 3).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * IN_W * IN_H * CLASSES).order(ByteOrder.nativeOrder())
    private val small = IntArray(IN_W * IN_H)
    private val maskPixels = IntArray(IN_W * IN_H)
    private val previous = FloatArray(IN_W * IN_H)
    private val mask = Bitmap.createBitmap(IN_W, IN_H, Bitmap.Config.ARGB_8888)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    private val modelThread = Executors.newSingleThreadExecutor()
    private val modelBusy = AtomicBoolean(false)
    private val maskLock = Any()

    // Frames are composed in this private buffer; the UI only ever gets an immutable copy, so it
    // can't draw a frame that is being rewritten (which could flash the unmasked background).
    private var work: Bitmap? = null
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    @Volatile private var closed = false

    private var frames = 0
    private var busyMs = 0L
    @Volatile private var inferMs = 0L
    @Volatile private var inferRuns = 0
    @Volatile private var lastRunMs = 0L
    @Volatile private var coverage = 0.0
    private var windowStart = 0L
    private var firstLogged = false

    override fun analyze(image: ImageProxy) {
        if (closed) { image.close(); return }
        val t0 = SystemClock.elapsedRealtime()
        val frame = try {
            square(upright(image.toBitmap(), image.imageInfo.rotationDegrees))
        } finally {
            image.close()
        }
        try {
            // Hand the newest frame to the model if it's free; otherwise it keeps working on an
            // older one and this frame is shown with the latest mask.
            if (modelBusy.compareAndSet(false, true)) {
                val scaled = Bitmap.createScaledBitmap(frame, IN_W, IN_H, true)
                scaled.getPixels(small, 0, IN_W, 0, 0, IN_W, IN_H)
                if (scaled !== frame) scaled.recycle()
                modelThread.execute {
                    try {
                        val m0 = SystemClock.elapsedRealtime()
                        segment()
                        lastRunMs = SystemClock.elapsedRealtime() - m0
                        inferMs += lastRunMs; inferRuns++
                    } catch (e: Exception) {
                        Log.w(SnapService.TAG, "cutout: segmentation failed: $e")
                    } finally {
                        modelBusy.set(false)
                    }
                }
            }
            val out = compose(frame)
            if (!closed) onFrame(out)
            stats(SystemClock.elapsedRealtime() - t0, frame.width, frame.height)
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "cutout: segmentation failed: $e")
        } finally {
            frame.recycle()
        }
    }

    /** Stops the model; frames that are still in flight are dropped. Call from the analyzer's thread or after it stops. */
    fun close() {
        closed = true
        runCatching {
            modelThread.execute {
                synchronized(interpreterLock) { runCatching { interpreter?.close() }; interpreter = null }
            }
        }
        modelThread.shutdown()
    }

    /**
     * Built on first use, on the model thread. The CPU (XNNPACK): LiteRT's GPU delegate was tried
     * on an x86 Googlebook and doesn't work there (OpenCL is clvk: 18 s+ to compile, then a crash;
     * OpenGL via ANGLE: fails to initialize).
     */
    private fun interpreter(): Interpreter =
        interpreter ?: Interpreter(loadModel(ctx), Interpreter.Options().setNumThreads(THREADS)).also { interpreter = it }

    private fun upright(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0 && !mirror) return src
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }

    /** The centered square of [src] (the bubble shows a square; the model takes one). */
    private fun square(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (src.width == side && src.height == side) return src
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side).also { src.recycle() }
    }

    /** Runs the model on the pixels in [small] and leaves the alpha mask in [mask]. Model thread. */
    private fun segment() {
        input.rewind()
        for (c in small) {
            input.putFloat(((c shr 16) and 0xFF) / 255f)
            input.putFloat(((c shr 8) and 0xFF) / 255f)
            input.putFloat((c and 0xFF) / 255f)
        }
        input.rewind(); output.rewind()
        synchronized(interpreterLock) {
            if (closed) return
            interpreter().run(input, output)
        }
        output.rewind()
        // Blending with the previous mask calms edge flicker when masks come fast; when they come
        // slowly it only drags the old outline along behind a moving person, so it's off then.
        val blend = if (lastRunMs in 1..FAST_MASK_MS) 0.4f else 0f
        var covered = 0
        for (i in 0 until IN_W * IN_H) {
            val person = output.getFloat() // this model's one channel is the person probability
            val m = (1f - blend) * person + blend * previous[i]
            previous[i] = m
            val a = smoothstep(m)
            if (a > 127) covered++
            maskPixels[i] = a shl 24
        }
        coverage = covered.toDouble() / (IN_W * IN_H)
        synchronized(maskLock) { mask.setPixels(maskPixels, 0, IN_W, 0, 0, IN_W, IN_H) }
    }

    /**
     * The frame with the (bilinearly upscaled) mask as its alpha, as a new immutable bitmap. At
     * most [MAX_OUT] px square: the large bubble is ~415 px, so more would only cost memory.
     */
    private fun compose(frame: Bitmap): Bitmap {
        val side = minOf(frame.width, MAX_OUT)
        val buf = work?.takeIf { it.width == side }
            ?: Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { work = it }
        val canvas = Canvas(buf)
        val dst = Rect(0, 0, side, side)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(frame, null, dst, framePaint)
        synchronized(maskLock) { canvas.drawBitmap(mask, null, dst, maskPaint) }
        return buf.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun smoothstep(m: Float): Int {
        val t = ((m - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
        return (t * t * (3 - 2 * t) * 255).toInt()
    }

    private fun stats(ms: Long, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!firstLogged) {
            firstLogged = true
            Log.i(SnapService.TAG, "cutout: first frame ${w}x$h in ${ms}ms")
            windowStart = now
        }
        frames++; busyMs += ms
        if (now - windowStart >= 5000) {
            val secs = (now - windowStart) / 1000.0
            val runs = inferRuns
            Log.i(
                SnapService.TAG,
                "cutout: video %.1f fps (%d ms/frame), mask %.1f fps (model %d ms), person %.0f%% of frame"
                    .format(frames / secs, busyMs / frames, runs / secs, if (runs > 0) inferMs / runs else 0, 100 * coverage),
            )
            frames = 0; busyMs = 0; inferMs = 0; inferRuns = 0; windowStart = now
        }
    }

    private companion object {
        const val IN_W = 256
        const val IN_H = 256
        const val CLASSES = 1
        const val MAX_OUT = 512
        const val THREADS = 6
        /** Masks faster than this get blended with the previous one (see segment()). */
        const val FAST_MASK_MS = 50L
        const val EDGE_LOW = 0.35f
        const val EDGE_HIGH = 0.65f
        const val MODEL = "models/selfie_segmenter.tflite"

        fun loadModel(ctx: Context): ByteBuffer {
            val bytes = ctx.assets.open(MODEL).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        }
    }
}
