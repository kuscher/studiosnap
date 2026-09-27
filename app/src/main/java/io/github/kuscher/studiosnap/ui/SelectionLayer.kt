package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.capture.CaptureKind
import io.github.kuscher.studiosnap.capture.CaptureSession
import io.github.kuscher.studiosnap.capture.SelPhase
import kotlin.math.roundToInt

private val Accent = Color(0xFFE4502B)
private val Scrim = Color(0f, 0f, 0f, 0.44f)
private val ScrimLight = Color(0f, 0f, 0f, 0.20f)

/** The full-screen freeze + selection surface behind the bar. */
@Composable
fun SelectionLayer(session: CaptureSession, adjustBeforeCapture: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(session, session.source, session.mode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    session.onDragStart(start)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            if ((change.position - start).getDistance() > 6f) moved = true
                            if (moved) session.onDrag(start, change.position, square = false, fromCenter = false)
                            change.consume()
                        } else {
                            if (moved) {
                                val rect = session.onDragEnd()
                                if (rect != null && !adjustBeforeCapture) session.captureArea(rect, CaptureKind.AREA)
                            } else {
                                session.cancelDrag()
                                session.tapAt(change.position)
                            }
                            break
                        }
                    }
                }
            }
            .pointerInput(session, session.source) {
                // hover highlighting (mouse move without a press)
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move && event.changes.none { it.pressed }) {
                            session.onHover(event.changes.first().position)
                        }
                    }
                }
            },
    ) {
        val frozen = session.frozen
        if (frozen != null) {
            Image(frozen, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        Canvas(Modifier.fillMaxSize()) {
            val full = Rect(0f, 0f, size.width, size.height)
            val sel = session.selection
            val hover = session.hover
            when {
                sel != null -> {
                    drawDimAround(sel, full, Scrim)
                    drawSelectionBorder(sel)
                }
                hover != null && session.source != io.github.kuscher.studiosnap.ui.Source.AREA -> {
                    drawDimAround(hover.rect, full, Scrim)
                    drawRect(Accent, topLeft = hover.rect.topLeft, size = Size(hover.rect.width, hover.rect.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                }
                session.phase == SelPhase.AIM && session.source == io.github.kuscher.studiosnap.ui.Source.AREA -> {
                    drawRect(ScrimLight, size = size)
                    // crosshair at the pointer
                    val p = session.pointer
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(0f, p.y), Offset(size.width, p.y), strokeWidth = 1f)
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1f)
                }
            }
        }
        // Dimensions pill
        val sel = session.selection
        if (sel != null) {
            val maxX = (session.displaySize?.first ?: 1920)
            Box(
                Modifier
                    .offset {
                        val y = if (sel.top > 90f) (sel.top - 34f) else (sel.top + 8f)
                        IntOffset(sel.left.roundToInt().coerceIn(6, maxX - 140), y.roundToInt())
                    }
                    .background(Color(0xE6101216), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                HudText("${sel.width.roundToInt()} × ${sel.height.roundToInt()}", size = 12, color = Color.White)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDimAround(sel: Rect, full: Rect, color: Color) {
    // four rectangles around the selection
    drawRect(color, topLeft = Offset(0f, 0f), size = Size(full.width, sel.top))
    drawRect(color, topLeft = Offset(0f, sel.bottom), size = Size(full.width, full.height - sel.bottom))
    drawRect(color, topLeft = Offset(0f, sel.top), size = Size(sel.left, sel.height))
    drawRect(color, topLeft = Offset(sel.right, sel.top), size = Size(full.width - sel.right, sel.height))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionBorder(sel: Rect) {
    drawRect(Color(0f, 0f, 0f, 0.55f), topLeft = Offset(sel.left - 1f, sel.top - 1f), size = Size(sel.width + 2f, sel.height + 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
    drawRect(Color.White, topLeft = sel.topLeft, size = Size(sel.width, sel.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
}
