package io.github.kuscher.studiosnap.record

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import io.github.kuscher.studiosnap.service.ComposeOverlay
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.ui.SymText
import io.github.kuscher.studiosnap.util.Settings
import io.github.kuscher.studiosnap.util.Sym
import kotlin.math.hypot

/**
 * The floating camera bubble, like ChromeOS's: a live camera preview in a small accessibility
 * overlay window that sits above everything, so a screen recording captures it exactly as you see
 * it. Drag it anywhere and it snaps to the nearest corner. Hover (or tap) for size, shape and
 * switch-camera controls. Size, shape, corner and camera persist in [Settings].
 *
 * The camera is bound to the overlay window's own lifecycle, so it only runs while the bubble is
 * on screen. No camera foreground service is needed: the accessibility binding already gives the
 * process Android's camera capability (on device, `dumpsys activity processes` shows `curCapability=LCMN…`).
 */
class CameraBubble(private val ctx: Context, private val settings: Settings) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var overlay: ComposeOverlay? = null
    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var cameras: List<CameraInfo> = emptyList()
    private var cameraId: String? = null
    private var snap: ValueAnimator? = null
    /** The current preview's stream-state observer, removed when that preview goes away. */
    private var streamObserver: Pair<PreviewView, Observer<PreviewView.StreamState>>? = null

    var large by mutableStateOf(settings.bubbleLarge)
        private set
    var square by mutableStateOf(settings.bubbleSquare)
        private set
    var cameraCount by mutableStateOf(0)
        private set

    val shown: Boolean get() = overlay != null

    /** True while the bubble is on screen showing the real camera image. */
    val showingCamera: Boolean get() = shown && !testPattern

    fun show() {
        if (overlay != null) return
        val px = windowPx()
        val (x, y) = cornerOffset(settings.bubbleCorner, px)
        val ov = ComposeOverlay(
            ctx, widthSpec = px, heightSpec = px, gravity = Gravity.CENTER,
            noLimits = false, offsetX = x, offsetY = y,
        )
        overlay = ov
        ov.show { BubbleContent(this) }
        Log.i(SnapService.TAG, "bubble shown corner=${settings.bubbleCorner} large=$large square=$square")
    }

    fun hide() {
        val ov = overlay ?: return
        overlay = null
        snap?.cancel()
        runCatching { provider?.unbindAll() }
        previewView = null
        ov.destroy()
        Log.i(SnapService.TAG, "bubble hidden")
    }

    /** Re-adds the window so it sits above an overlay that was added after it (the bar, the pill). */
    fun bringToFront() {
        val ov = overlay ?: return
        ov.dismiss()
        ov.show { BubbleContent(this) }
    }

    fun toggleSize() {
        large = !large
        settings.bubbleLarge = large
        val ov = overlay ?: return
        val px = windowPx()
        ov.resize(px, px)
        val (x, y) = cornerOffset(settings.bubbleCorner, px)
        ov.moveTo(x, y)
    }

    fun toggleShape() {
        square = !square
        settings.bubbleSquare = square
    }

    fun switchCamera() {
        if (cameras.size < 2) return
        val i = cameras.indexOfFirst { idOf(it) == cameraId }
        settings.bubbleCameraId = idOf(cameras[(i + 1) % cameras.size])
        bindCamera()
    }

    fun moveToCorner(c: Int) {
        settings.bubbleCorner = c.coerceIn(0, 3)
        val ov = overlay ?: return
        val (x, y) = cornerOffset(settings.bubbleCorner, ov.params.width)
        ov.moveTo(x, y)
    }

    fun describe(): String {
        val p = overlay?.params
        return "bubble shown=$shown window=${p?.width}x${p?.height} at=(${p?.x},${p?.y}) corner=${settings.bubbleCorner} " +
            "large=$large square=$square camera=$cameraId of $cameraCount test=$testPattern"
    }

    // ---- camera ----

    internal fun onPreviewView(pv: PreviewView) {
        previewView = pv
        val owner = overlay ?: return
        val observer = Observer<PreviewView.StreamState> { Log.i(SnapService.TAG, "bubble stream $it") }
        pv.previewStreamState.observe(owner, observer)
        streamObserver = pv to observer
        bindCamera()
    }

    internal fun onPreviewGone(pv: PreviewView) {
        // Always drop this preview's observer (the overlay's lifecycle outlives each preview when
        // the bubble is re-added); only unbind if it's still the current preview.
        streamObserver?.takeIf { it.first === pv }?.let { (v, o) -> v.previewStreamState.removeObserver(o); streamObserver = null }
        if (previewView !== pv) return
        previewView = null
        runCatching { provider?.unbindAll() }
    }

    private fun bindCamera() {
        val pv = previewView ?: return
        val owner = overlay ?: return
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val p = runCatching { future.get() }.getOrElse { Log.w(SnapService.TAG, "camera provider: $it"); return@addListener }
            if (overlay !== owner || previewView !== pv) return@addListener // hidden or replaced meanwhile
            provider = p
            cameras = p.availableCameraInfos
            cameraCount = cameras.size
            val info = pick(cameras) ?: run { Log.w(SnapService.TAG, "bubble: no camera"); return@addListener }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, info.cameraSelector, preview)
            } catch (e: Exception) {
                Log.w(SnapService.TAG, "bubble: camera bind failed: $e"); return@addListener
            }
            // PreviewView mirrors a front camera itself; mirror external webcams too, so the
            // bubble behaves like a mirror whichever camera it shows.
            pv.scaleX = if (info.lensFacing == CameraSelector.LENS_FACING_EXTERNAL) -1f else 1f
            cameraId = idOf(info)
            Log.i(SnapService.TAG, "bubble camera $cameraId facing=${info.lensFacing} of ${cameras.size}")
        }, ctx.mainExecutor)
    }

    /** The saved camera, else the front camera, else an external webcam, else whatever exists. */
    private fun pick(list: List<CameraInfo>): CameraInfo? =
        list.firstOrNull { idOf(it) == settings.bubbleCameraId }
            ?: list.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            ?: list.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_EXTERNAL }
            ?: list.firstOrNull()

    @OptIn(ExperimentalCamera2Interop::class)
    private fun idOf(info: CameraInfo): String = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrDefault("?")

    // ---- window geometry and dragging ----

    private fun windowPx(): Int = (((if (large) LARGE_DP else SMALL_DP) + 2 * PAD_DP) * density).toInt()

    /** Window offset from screen centre (accessibility overlays ignore gravity) for a corner. */
    private fun cornerOffset(corner: Int, px: Int): Pair<Int, Int> {
        val metrics = wm.maximumWindowMetrics
        val w = metrics.bounds.width(); val h = metrics.bounds.height()
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val margin = (MARGIN_DP * density).toInt()
        // The taskbar's reported inset can undershoot, so keep a floor under the bottom corners.
        val bottom = maxOf(insets.bottom, (TASKBAR_FLOOR_DP * density).toInt())
        val left = corner % 2 == 0
        val top = corner < 2
        val cx = if (left) insets.left + margin + px / 2 else w - insets.right - margin - px / 2
        val cy = if (top) insets.top + margin + px / 2 else h - bottom - margin - px / 2
        return (cx - w / 2) to (cy - h / 2)
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    /** Drag in raw screen coordinates (the window moves under the pointer); a click is a tap. */
    internal fun onTouch(ev: MotionEvent, onTap: () -> Unit): Boolean {
        val ov = overlay ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snap?.cancel()
                downX = ev.rawX; downY = ev.rawY
                startX = ov.params.x; startY = ov.params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) ov.moveTo(startX + dx.toInt(), startY + dy.toInt())
            }
            MotionEvent.ACTION_UP -> if (dragging) snapToNearestCorner() else onTap()
            MotionEvent.ACTION_CANCEL -> if (dragging) snapToNearestCorner()
        }
        return true
    }

    private fun snapToNearestCorner() {
        val ov = overlay ?: return
        // Offsets are from the screen centre, so the sign says which half the bubble is in.
        val corner = (if (ov.params.y > 0) 2 else 0) + (if (ov.params.x > 0) 1 else 0)
        settings.bubbleCorner = corner
        val (tx, ty) = cornerOffset(corner, ov.params.width)
        val fx = ov.params.x; val fy = ov.params.y
        snap = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val t = it.animatedValue as Float
                ov.moveTo(fx + ((tx - fx) * t).toInt(), fy + ((ty - fy) * t).toInt())
            }
            start()
        }
        Log.i(SnapService.TAG, "bubble snapped to corner $corner")
    }

    companion object {
        private const val SMALL_DP = 160
        private const val LARGE_DP = 256
        private const val PAD_DP = 6
        private const val MARGIN_DP = 16
        private const val TASKBAR_FLOOR_DP = 72

        /**
         * adb visual checks: draw a test pattern instead of the camera image. Process-wide, not
         * per bubble: Android can recreate the accessibility service (and with it the bubble)
         * around a permission dialog, and the pattern must survive that.
         */
        var testPattern by mutableStateOf(false)
    }
}

@SuppressLint("ClickableViewAccessibility")
@kotlin.OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BubbleContent(b: CameraBubble) {
    var hovered by remember { mutableStateOf(false) }
    var tapped by remember { mutableStateOf(false) }
    val shape = if (b.square) RoundedCornerShape(22) else CircleShape
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        when (e.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                        }
                    }
                }
            }
            .padding(6.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(Color.Black),
    ) {
        if (CameraBubble.testPattern) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFC23B1A), Color(0xFF3B6FD6)))))
        } else {
            var view by remember { mutableStateOf<PreviewView?>(null) }
            AndroidView(
                factory = { c ->
                    PreviewView(c).apply {
                        // TextureView, so the Compose clip (circle / rounded square) applies.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }.also { view = it; b.onPreviewView(it) }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) { onDispose { view?.let { b.onPreviewGone(it) } } }
        }
        // Drag and tap surface; the controls sit above it and take their own clicks.
        Box(Modifier.fillMaxSize().pointerInteropFilter { ev -> b.onTouch(ev) { tapped = !tapped } })
        Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.85f), shape))
        if (hovered || tapped) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(Color(0x99000000), CircleShape)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BubbleButton(if (b.large) Sym.CLOSE_FULLSCREEN else Sym.OPEN_IN_FULL, if (b.large) "Smaller" else "Larger") { b.toggleSize() }
                BubbleButton(if (b.square) Sym.CIRCLE else Sym.SQUARE, if (b.square) "Circle" else "Rounded square") { b.toggleShape() }
                if (b.cameraCount > 1) BubbleButton(Sym.CAMERASWITCH, "Switch camera") { b.switchCamera() }
            }
        }
    }
}

@Composable
private fun BubbleButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { SymText(glyph, size = 18, color = Color.White) }
}
