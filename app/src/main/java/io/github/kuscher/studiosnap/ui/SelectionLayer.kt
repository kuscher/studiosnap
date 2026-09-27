package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.capture.CaptureKind
import io.github.kuscher.studiosnap.capture.CaptureSession
import io.github.kuscher.studiosnap.capture.SelPhase
import io.github.kuscher.studiosnap.util.Sym
import kotlin.math.roundToInt

private val Accent = Color(0xFFE4502B)
private val Scrim = Color(0f, 0f, 0f, 0.44f)
private val ScrimLight = Color(0f, 0f, 0f, 0.20f)
private val Pill = Color(0xE6101216)

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
                                session.cancelDrag(); session.tapAt(change.position)
                            }
                            break
                        }
                    }
                }
            }
            .pointerInput(session, session.source) {
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
        val sel = session.selection
        val hover = session.hover
        val areaAim = session.phase == SelPhase.AIM && (session.source == Source.AREA || session.source == Source.TEXT)

        Canvas(Modifier.fillMaxSize()) {
            when {
                sel != null -> { drawDimAround(sel, size.width, size.height, Scrim); drawSelectionBorder(sel) }
                hover != null -> {
                    drawDimAround(hover.rect, size.width, size.height, Scrim)
                    val r = hover.rect
                    // Round the highlight to follow the UI. Nodes only expose a bounding box, so
                    // approximate the corner: small controls round toward a pill, large containers
                    // stay gently rounded.
                    val radius = if (hover.isElement) minOf(r.height / 2f, r.width / 2f, 22f) else 16f
                    val cr = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    val sz = Size(r.width, r.height)
                    drawRoundRect(Accent.copy(alpha = 0.10f), topLeft = r.topLeft, size = sz, cornerRadius = cr)
                    drawRoundRect(Accent, topLeft = r.topLeft, size = sz, cornerRadius = cr, style = Stroke(width = if (hover.isElement) 2.5f else 3f))
                }
                areaAim -> {
                    drawRect(ScrimLight, size = size)
                    val p = session.pointer
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(0f, p.y), Offset(size.width, p.y), strokeWidth = 1f)
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1f)
                }
            }
        }

        // Dimensions pill on the selection
        if (sel != null) {
            val maxX = session.displaySize?.first ?: 1920
            InfoPill("${sel.width.roundToInt()} × ${sel.height.roundToInt()}", null) {
                val y = if (sel.top > 90f) sel.top - 34f else sel.top + 8f
                IntOffset(sel.left.roundToInt().coerceIn(6, maxX - 140), y.roundToInt())
            }
        }

        // Hover info tag (what a click will grab)
        if (sel == null && hover != null) {
            val maxX = session.displaySize?.first ?: 1920
            val icon = when {
                hover.isElement -> Sym.ADS_CLICK
                session.source == Source.SCREEN -> Sym.FULLSCREEN
                else -> Sym.SELECT_WINDOW
            }
            val dims = "${hover.rect.width.roundToInt()} × ${hover.rect.height.roundToInt()}"
            InfoPill(hover.label.take(40), icon, dims) {
                val r = hover.rect
                val y = if (hover.isElement) (r.top - 40f).coerceAtLeast(46f) else (r.top + 8f)
                IntOffset(r.left.roundToInt().coerceIn(6, maxX - 340), y.roundToInt())
            }
        }

        // Loupe magnifier while aiming an area
        if (areaAim && frozen != null) {
            Loupe(session, frozen)
        }
    }
}

@Composable
private fun InfoPill(text: String, icon: String?, sub: String? = null, offset: androidx.compose.ui.unit.Density.() -> IntOffset) {
    Row(
        Modifier
            .offset(offset)
            .background(Pill, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) SymText(icon, size = 16, color = Color.White)
        HudText(text, size = 12, color = Color.White)
        if (sub != null) HudText(sub, size = 12, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun Loupe(session: CaptureSession, frozen: ImageBitmap) {
    val p = session.pointer
    val disp = session.displaySize ?: return
    val loupePx = 132
    val srcR = 11 // source radius; 132 / (2*6)
    val color = Color(session.colorAt(p))
    val hex = "#%02X%02X%02X".format((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    Box(Modifier.offset {
        var lx = p.x.roundToInt() + 26; var ly = p.y.roundToInt() + 26
        if (lx + 150 > disp.first) lx = p.x.roundToInt() - 160
        if (ly + 180 > disp.second) ly = p.y.roundToInt() - 190
        IntOffset(lx.coerceAtLeast(6), ly.coerceAtLeast(46))
    }) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(loupePx.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image = frozen,
                        srcOffset = IntOffset((p.x - srcR).roundToInt(), (p.y - srcR).roundToInt()),
                        srcSize = IntSize(srcR * 2, srcR * 2),
                        dstOffset = IntOffset(0, 0),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.None,
                    )
                    // center pixel marker
                    val c = size.width / 2f
                    drawRect(Accent, topLeft = Offset(c - 4f, c - 4f), size = Size(8f, 8f), style = Stroke(width = 1.5f))
                }
            }
            Row(
                Modifier.padding(top = 6.dp).background(Pill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color).border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
                HudText("${p.x.roundToInt()}, ${p.y.roundToInt()} · $hex", size = 11, color = Color.White)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDimAround(sel: Rect, w: Float, h: Float, color: Color) {
    drawRect(color, topLeft = Offset(0f, 0f), size = Size(w, sel.top))
    drawRect(color, topLeft = Offset(0f, sel.bottom), size = Size(w, h - sel.bottom))
    drawRect(color, topLeft = Offset(0f, sel.top), size = Size(sel.left, sel.height))
    drawRect(color, topLeft = Offset(sel.right, sel.top), size = Size(w - sel.right, sel.height))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionBorder(sel: Rect) {
    drawRect(Color(0f, 0f, 0f, 0.55f), topLeft = Offset(sel.left - 1f, sel.top - 1f), size = Size(sel.width + 2f, sel.height + 2f), style = Stroke(width = 1f))
    drawRect(Color.White, topLeft = sel.topLeft, size = Size(sel.width, sel.height), style = Stroke(width = 1.5f))
}
