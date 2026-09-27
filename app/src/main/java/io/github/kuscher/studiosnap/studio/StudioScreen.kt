package io.github.kuscher.studiosnap.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.capture.Output
import io.github.kuscher.studiosnap.ui.HudButton
import io.github.kuscher.studiosnap.ui.HudText
import io.github.kuscher.studiosnap.ui.LocalHud
import io.github.kuscher.studiosnap.ui.ProvideHud
import io.github.kuscher.studiosnap.ui.SymText
import io.github.kuscher.studiosnap.util.Sym

private data class Layout(val scale: Float, val offX: Float, val offY: Float)

@Composable
fun StudioScreen(state: EditorState, dark: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val image = remember(state) { state.image.asImageBitmap() }
    var toast by remember { mutableStateOf<String?>(null) }
    ProvideHud(dark) {
        val hud = LocalHud.current
        Column(Modifier.fillMaxSize().background(hud.surface)) {
            // top bar
            Row(
                Modifier.fillMaxWidth().background(hud.track).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HudText("StudioSnap · Studio", size = 15, color = hud.ink)
                HudText("${state.imgW} × ${state.imgH}", size = 12, color = hud.muted)
                Box(Modifier.weight(1f))
                HudButton(Sym.UNDO, "Undo", enabled = state.canUndo, onClick = { state.undo() })
                HudButton(Sym.REDO, "Redo", enabled = state.canRedo, onClick = { state.redo() })
                Pill("Copy", Sym.CONTENT_COPY, hud.track, hud.ink) {
                    Output.copyToClipboard(ctx, state.export(), Output.defaultName()); toast = "Copied to clipboard"
                }
                Pill("Save", Sym.CHECK, hud.primary, hud.onPrimary) {
                    Output.saveToGallery(ctx, state.export(), Output.defaultName()); toast = "Saved to Pictures/StudioSnap"
                }
                HudButton(Sym.CLOSE, "Close", onClick = onClose)
            }
            // work area
            Box(Modifier.fillMaxSize().background(hud.track)) {
                EditorCanvas(state, image)
                ToolStrip(state, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                PropertyBar(state, Modifier.align(Alignment.TopCenter).padding(top = 74.dp))
                toast?.let {
                    LaunchedToast(it) { toast = null }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).background(Color(0xE6101216), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                        HudText(it, size = 13, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedToast(key: Any, onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { kotlinx.coroutines.delay(1600); onDone() }
}

@Composable
private fun Pill(label: String, glyph: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).background(bg, CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { SymText(glyph, size = 18, color = fg); HudText(label, size = 13, color = fg) }
}

@Composable
private fun ToolStrip(state: EditorState, modifier: Modifier) {
    val hud = LocalHud.current
    Row(
        modifier.background(hud.surface, CircleShape).border(1.dp, hud.line, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tool.entries.forEach { t ->
            if (t == Tool.SELECT || t == Tool.TEXT || t == Tool.CROP) return@forEach // arrive in a later step
            HudButton(t.glyph, t.label, selected = state.tool == t, diameter = 40, onClick = { state.tool = t })
        }
    }
}

@Composable
private fun PropertyBar(state: EditorState, modifier: Modifier) {
    val hud = LocalHud.current
    Row(
        modifier.background(hud.surface, RoundedCornerShape(18.dp)).border(1.dp, hud.line, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Palette.forEach { c ->
            Box(
                Modifier.size(20.dp).background(c, CircleShape)
                    .border(2.dp, if (state.color == c) hud.ink else Color.Transparent, CircleShape)
                    .clickable { state.color = c },
            )
        }
        Box(Modifier.width(1.dp).height(20.dp).background(hud.line))
        Widths.forEachIndexed { i, w ->
            Box(
                Modifier.size(26.dp, 24.dp).background(if (state.width == w) hud.selBg else hud.track, RoundedCornerShape(7.dp)).clickable { state.width = w },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(14.dp).height((2 + i * 3).dp).background(hud.ink, CircleShape)) }
        }
    }
}

@Composable
private fun EditorCanvas(state: EditorState, image: ImageBitmap) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(canvas, state) {
        if (canvas.width == 0) Layout(1f, 0f, 0f)
        else {
            val s = minOf(canvas.width / state.imgW.toFloat(), canvas.height / state.imgH.toFloat()) * 0.9f
            Layout(s, (canvas.width - state.imgW * s) / 2f, (canvas.height - state.imgH * s) / 2f)
        }
    }
    var live by remember { mutableStateOf<Ann?>(null) }

    Canvas(
        Modifier.fillMaxSize().onSizeChanged { canvas = it }
            .pointerInput(state.tool, layout) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = toImage(down.position, layout)
                    val pts = mutableListOf(start)
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent(); val ch = e.changes.firstOrNull() ?: break
                        val ip = toImage(ch.position, layout)
                        if (ch.pressed) {
                            if ((ch.position - down.position).getDistance() > 4f) moved = true
                            pts.add(ip)
                            live = buildAnn(state, start, ip, pts, moved)
                            ch.consume()
                        } else {
                            if (state.tool == Tool.STEP && !moved) {
                                state.add(Ann.StepAnn(start, state.stepCounter++, state.color))
                            } else if (moved) {
                                buildAnn(state, start, ip, pts, true)?.let { state.add(it) }
                            }
                            live = null
                            break
                        }
                    }
                }
            },
    ) {
        withTransform({ translate(layout.offX, layout.offY); scale(layout.scale, layout.scale, pivot = Offset.Zero) }) {
            drawImage(image)
            state.anns.forEach { drawAnn(it) }
            live?.let { drawAnn(it) }
        }
    }
}

private fun toImage(p: Offset, l: Layout): Offset = Offset((p.x - l.offX) / l.scale, (p.y - l.offY) / l.scale)

private fun buildAnn(state: EditorState, start: Offset, cur: Offset, pts: List<Offset>, moved: Boolean): Ann? {
    if (!moved) return null
    return when (state.tool) {
        Tool.PEN -> Ann.Stroke(pts.toList(), state.color, state.width, highlight = false)
        Tool.HIGHLIGHT -> Ann.Stroke(pts.toList(), state.color, state.width, highlight = true)
        Tool.REDACT -> Ann.Redact(androidx.compose.ui.geometry.Rect(minOf(start.x, cur.x), minOf(start.y, cur.y), maxOf(start.x, cur.x), maxOf(start.y, cur.y)))
        Tool.ARROW, Tool.LINE, Tool.RECT, Tool.ELLIPSE -> Ann.ShapeAnn(state.tool, start, cur, state.color, state.width)
        else -> null
    }
}

// ---- Compose renderer (screen); mirrors drawAnnAndroid ----
private fun DrawScope.drawAnn(a: Ann) {
    when (a) {
        is Ann.Stroke -> {
            val path = androidx.compose.ui.graphics.Path()
            a.points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, a.color.copy(alpha = if (a.highlight) 0.35f else 1f), style = Stroke(width = if (a.highlight) a.width * 2.4f else a.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        is Ann.ShapeAnn -> {
            val tl = Offset(minOf(a.start.x, a.end.x), minOf(a.start.y, a.end.y))
            val sz = Size(kotlin.math.abs(a.end.x - a.start.x), kotlin.math.abs(a.end.y - a.start.y))
            when (a.tool) {
                Tool.RECT -> drawRect(a.color, topLeft = tl, size = sz, style = Stroke(width = a.width))
                Tool.ELLIPSE -> drawOval(a.color, topLeft = tl, size = sz, style = Stroke(width = a.width))
                Tool.LINE -> drawLine(a.color, a.start, a.end, strokeWidth = a.width, cap = StrokeCap.Round)
                Tool.ARROW -> drawArrow(a.start, a.end, a.color, a.width)
                else -> {}
            }
        }
        is Ann.TextAnn -> {}
        is Ann.StepAnn -> {
            drawCircle(a.color, radius = 26f, center = a.center)
            drawContext.canvas.nativeCanvas.drawText(
                a.number.toString(), a.center.x, a.center.y + 11f,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE; textSize = 30f
                    textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true
                },
            )
        }
        is Ann.Redact -> drawRect(Color.Black, topLeft = Offset(a.rect.left, a.rect.top), size = Size(a.rect.width, a.rect.height))
    }
}

private fun DrawScope.drawArrow(s: Offset, e: Offset, color: Color, w: Float) {
    drawLine(color, s, e, strokeWidth = w, cap = StrokeCap.Round)
    val ang = kotlin.math.atan2((e.y - s.y), (e.x - s.x))
    val head = (w * 3.2f).coerceAtLeast(22f)
    val a1 = ang - (Math.PI / 7).toFloat(); val a2 = ang + (Math.PI / 7).toFloat()
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(e.x, e.y)
    path.lineTo(e.x - head * kotlin.math.cos(a1), e.y - head * kotlin.math.sin(a1))
    path.lineTo(e.x - head * kotlin.math.cos(a2), e.y - head * kotlin.math.sin(a2))
    path.close()
    drawPath(path, color)
}
