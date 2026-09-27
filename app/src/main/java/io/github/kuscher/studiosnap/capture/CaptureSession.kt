package io.github.kuscher.studiosnap.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.ui.CaptureMode
import io.github.kuscher.studiosnap.ui.Source

/** A window in screen (px) coordinates, from the accessibility window list. */
data class WinInfo(val id: Int, val label: String, val rect: Rect, val z: Int)

/** What the pointer is currently over: a whole window, or a UI element inside one. */
data class Hover(val rect: Rect, val label: String, val isElement: Boolean, val winId: Int?)

enum class SelPhase { AIM, DRAG, ADJUST }

/** The kind of result produced, for the card label and later routing. */
enum class CaptureKind { AREA, ELEMENT, WINDOW, SCREEN }

/**
 * One capture session: the state behind the overlay while the bar is open. It freezes the screen
 * on open (capturing before the overlay is shown, so the bar never appears in the shot) and every
 * result is cropped from that frozen frame. Pointer coordinates are display pixels, 1:1 with the
 * full-screen overlay.
 */
class CaptureSession(
    val context: Context,
    private val listWindows: () -> List<WinInfo>,
    private val onResult: (Bitmap, CaptureKind, String) -> Unit,
    private val onDismiss: () -> Unit,
) {
    var mode by mutableStateOf(CaptureMode.SHOT)
    var source by mutableStateOf(Source.AREA)
    var collapsed by mutableStateOf(false)
    var timerSeconds by mutableStateOf(0)

    var frozen by mutableStateOf<ImageBitmap?>(null)
        private set
    private var frozenBmp: Bitmap? = null

    var phase by mutableStateOf(SelPhase.AIM)
    var selection by mutableStateOf<Rect?>(null)
    var hover by mutableStateOf<Hover?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var windows: List<WinInfo> = emptyList()
        private set

    val recSources = listOf(Source.SCREEN, Source.WINDOW, Source.AREA)
    val shotSources = listOf(Source.AREA, Source.WINDOW, Source.SCREEN, Source.SCROLL, Source.TEXT)
    val sources get() = if (mode == CaptureMode.REC) recSources else shotSources

    val displaySize get() = frozenBmp?.let { it.width to it.height }

    /** Called once the frozen frame is ready and the overlay is about to show. */
    fun onFrozen(bmp: Bitmap?, initialSource: Source?) {
        frozenBmp = bmp
        frozen = bmp?.asImageBitmap()
        windows = listWindows()
        if (initialSource != null) source = initialSource
        phase = SelPhase.AIM
        if (source == Source.SCREEN) hover = Hover(fullRect(), "Display 1", false, null)
    }

    private fun fullRect(): Rect {
        val b = frozenBmp
        return if (b != null) Rect(0f, 0f, b.width.toFloat(), b.height.toFloat()) else Rect.Zero
    }

    fun changeMode(m: CaptureMode) {
        mode = m
        if (m == CaptureMode.REC && source !in recSources) source = Source.AREA
        selection = null; phase = SelPhase.AIM
        hover = if (source == Source.SCREEN) Hover(fullRect(), "Display 1", false, null) else null
    }

    fun changeSource(s: Source) {
        if (mode == CaptureMode.REC && s !in recSources) return
        source = s
        selection = null; phase = SelPhase.AIM
        hover = if (s == Source.SCREEN) Hover(fullRect(), "Display 1", false, null) else null
    }

    // ---- pointer handling on the frozen screen ----

    fun onHover(p: Offset) {
        pointer = p
        if (phase != SelPhase.AIM) return
        hover = when (source) {
            Source.SCREEN -> Hover(fullRect(), "Display 1", false, null)
            Source.WINDOW, Source.SCROLL -> topWindowAt(p)?.let { Hover(it.rect, it.label, false, it.id) }
            else -> null // element snapping arrives in a later step
        }
    }

    fun onDragStart(p: Offset) {
        pointer = p
        if (source == Source.WINDOW || source == Source.SCROLL || source == Source.SCREEN) return
        selection = Rect(p, p); phase = SelPhase.DRAG; hover = null
    }

    fun onDrag(start: Offset, cur: Offset, square: Boolean, fromCenter: Boolean) {
        pointer = cur
        if (phase != SelPhase.DRAG) return
        var x1 = cur.x; var y1 = cur.y
        if (square) {
            val s = maxOf(kotlin.math.abs(x1 - start.x), kotlin.math.abs(y1 - start.y))
            x1 = start.x + Math.signum((x1 - start.x).let { if (it == 0f) 1f else it }) * s
            y1 = start.y + Math.signum((y1 - start.y).let { if (it == 0f) 1f else it }) * s
        }
        selection = if (fromCenter) norm(2 * start.x - x1, 2 * start.y - y1, x1, y1)
        else norm(start.x, start.y, x1, y1)
    }

    fun cancelDrag() { if (phase == SelPhase.DRAG) { selection = null; phase = SelPhase.AIM } }

    fun onDragEnd(): Rect? {
        val sel = selection
        if (phase == SelPhase.DRAG && sel != null && sel.width >= 6 && sel.height >= 6) {
            phase = SelPhase.ADJUST
            return sel
        }
        selection = null; phase = SelPhase.AIM
        return null
    }

    fun tapAt(p: Offset): Boolean {
        // A click without a drag: grab the hovered window/screen.
        when (source) {
            Source.SCREEN -> { captureScreen(); return true }
            Source.WINDOW -> { topWindowAt(p)?.let { captureWindow(it); return true } }
            else -> {}
        }
        return false
    }

    fun primary() {
        when (source) {
            Source.SCREEN -> captureScreen()
            Source.WINDOW -> hover?.winId?.let { id -> windows.find { it.id == id }?.let { captureWindow(it) } }
            Source.AREA, Source.TEXT -> selection?.let { captureArea(it, CaptureKind.AREA) }
            Source.SCROLL -> {} // Phase 3
        }
    }

    val primaryEnabled: Boolean
        get() = when (source) {
            Source.SCREEN -> true
            Source.WINDOW, Source.SCROLL -> hover?.winId != null
            else -> phase == SelPhase.ADJUST && selection != null
        }

    // ---- capture (crop from the frozen frame for now; true window capture arrives next step) ----

    private fun captureScreen() { frozenBmp?.let { onResult(it, CaptureKind.SCREEN, "Screen") } ; finish() }

    private fun captureWindow(w: WinInfo) {
        cropFrozen(w.rect)?.let { onResult(it, CaptureKind.WINDOW, "Window · ${w.label}") }
        finish()
    }

    fun captureArea(rect: Rect, kind: CaptureKind) {
        cropFrozen(rect)?.let { onResult(it, kind, if (kind == CaptureKind.ELEMENT) "Element" else "Area") }
        finish()
    }

    private fun cropFrozen(r: Rect): Bitmap? {
        val b = frozenBmp ?: return null
        val x = r.left.toInt().coerceIn(0, b.width - 1)
        val y = r.top.toInt().coerceIn(0, b.height - 1)
        val w = r.width.toInt().coerceIn(1, b.width - x)
        val h = r.height.toInt().coerceIn(1, b.height - y)
        return try { Bitmap.createBitmap(b, x, y, w, h) } catch (e: Exception) { Log.w(SnapService.TAG, "crop failed: $e"); null }
    }

    private fun finish() { onDismiss() }
    fun cancel() { onDismiss() }

    private fun topWindowAt(p: Offset): WinInfo? =
        windows.filter { it.rect.contains(p) }.maxByOrNull { it.z }

    private fun norm(x0: Float, y0: Float, x1: Float, y1: Float): Rect {
        val b = frozenBmp
        val maxW = b?.width?.toFloat() ?: Float.MAX_VALUE
        val maxH = b?.height?.toFloat() ?: Float.MAX_VALUE
        val l = minOf(x0, x1).coerceIn(0f, maxW); val t = minOf(y0, y1).coerceIn(0f, maxH)
        val rr = maxOf(x0, x1).coerceIn(0f, maxW); val bb = maxOf(y0, y1).coerceIn(0f, maxH)
        return Rect(l, t, rr, bb)
    }
}
