package io.github.kuscher.studiosnap.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.capture.Output
import io.github.kuscher.studiosnap.ui.HudButton
import io.github.kuscher.studiosnap.ui.HudText
import io.github.kuscher.studiosnap.ui.LocalHud
import io.github.kuscher.studiosnap.ui.ProvideHud
import io.github.kuscher.studiosnap.ui.SymText
import io.github.kuscher.studiosnap.util.Sym

private data class Layout(val scale: Float, val offX: Float, val offY: Float, val pad: Float)

@Composable
fun StudioScreen(state: EditorState, dark: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }
    ProvideHud(dark) {
        val hud = LocalHud.current
        Column(Modifier.fillMaxSize().background(hud.surface)) {
            Row(
                Modifier.fillMaxWidth().background(hud.track).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HudText("StudioSnap · Studio", size = 15, color = hud.ink)
                HudText("${state.imgW} × ${state.imgH}", size = 12, color = hud.muted)
                Box(Modifier.weight(1f))
                HudButton(Sym.UNDO, "Undo", enabled = state.canUndo, onClick = { state.undo() })
                HudButton(Sym.REDO, "Redo", enabled = state.canRedo, onClick = { state.redo() })
                Pill("Copy", Sym.CONTENT_COPY, hud.track, hud.ink) { Output.copyToClipboard(ctx, state.export(), Output.defaultName()); toast = "Copied to clipboard" }
                Pill("Save", Sym.CHECK, hud.primary, hud.onPrimary) { Output.saveToGallery(ctx, state.export(), Output.defaultName()); toast = "Saved to Pictures/StudioSnap" }
                HudButton(Sym.CLOSE, "Close", onClick = onClose)
            }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(hud.track)) {
                    EditorCanvas(state) { toast = it }
                    ToolStrip(state, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                    PropertyBar(state, Modifier.align(Alignment.TopCenter).padding(top = 74.dp))
                    toast?.let {
                        LaunchedToast(it) { toast = null }
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).background(Color(0xE6101216), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                            HudText(it, size = 13, color = Color.White)
                        }
                    }
                }
                RightPanel(state, tab, { tab = it }, Modifier.width(288.dp).fillMaxHeight())
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
            HudButton(t.glyph, t.label, selected = state.tool == t, diameter = 40, onClick = { state.tool = t; state.selected = null })
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
        if (state.selected != null) {
            HudButton(Sym.DELETE, "Delete", diameter = 34, color = hud.rec, onClick = { state.deleteSelected() })
            Box(Modifier.width(1.dp).height(20.dp).background(hud.line))
        }
        Palette.forEach { c ->
            Box(Modifier.size(20.dp).background(c, CircleShape).border(2.dp, if (state.color == c) hud.ink else Color.Transparent, CircleShape).clickable { state.color = c })
        }
        Box(Modifier.width(1.dp).height(20.dp).background(hud.line))
        Widths.forEachIndexed { i, w ->
            Box(Modifier.size(26.dp, 24.dp).background(if (state.width == w) hud.selBg else hud.track, RoundedCornerShape(7.dp)).clickable { state.width = w }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(14.dp).height((2 + i * 3).dp).background(hud.ink, CircleShape))
            }
        }
    }
}

@Composable
private fun RightPanel(state: EditorState, tab: Int, onTab: (Int) -> Unit, modifier: Modifier) {
    val hud = LocalHud.current
    Column(modifier.background(hud.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().background(hud.track, RoundedCornerShape(12.dp)).padding(2.dp)) {
            listOf("Frame", "Layers").forEachIndexed { i, t ->
                Box(Modifier.weight(1f).background(if (tab == i) hud.surface else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onTab(i) }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    HudText(t, size = 13, color = hud.ink)
                }
            }
        }
        if (tab == 0) FramePanel(state) else LayersPanel(state)
    }
}

@Composable
private fun FramePanel(state: EditorState) {
    val hud = LocalHud.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HudText("BACKGROUND", size = 11, color = hud.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Bg.entries.forEach { b ->
                val brush = when (b) {
                    Bg.NONE -> Brush.linearGradient(listOf(Color(0xFFE2E5EA), Color.White))
                    Bg.AUTO -> Brush.linearGradient(listOf(hud.track, hud.track))
                    else -> { val cs = bgColors(b, state.bitmap); Brush.linearGradient(listOf(Color(cs[0]), Color(cs[1]))) }
                }
                Box(
                    Modifier.size(38.dp).background(brush, RoundedCornerShape(9.dp)).border(2.dp, if (state.bg == b) hud.ink else Color.Transparent, RoundedCornerShape(9.dp)).clickable { state.bg = b; if (b != Bg.NONE && state.padding == 0f) state.padding = state.imgW * 0.06f },
                    contentAlignment = Alignment.Center,
                ) { if (b == Bg.AUTO) HudText("Auto", size = 10, color = hud.ink) }
            }
        }
        HudText("PADDING", size = 11, color = hud.muted)
        Slider(value = state.padding, onValueChange = { state.padding = it }, valueRange = 0f..(state.imgW * 0.2f))
        HudText("CORNERS", size = 11, color = hud.muted)
        Slider(value = state.corners, onValueChange = { state.corners = it }, valueRange = 0f..80f)
    }
}

@Composable
private fun LayersPanel(state: EditorState) {
    val hud = LocalHud.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.anns.isEmpty()) HudText("No annotations yet.", size = 13, color = hud.muted)
        state.anns.asReversed().forEachIndexed { revIdx, a ->
            val idx = state.anns.lastIndex - revIdx
            val (glyph, name) = layerLabel(a)
            Row(
                Modifier.fillMaxWidth().background(if (state.selected == idx) hud.selBg else hud.track, RoundedCornerShape(9.dp)).clickable { state.selected = idx }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SymText(glyph, size = 17, color = if (state.selected == idx) hud.selInk else hud.muted)
                HudText(name, size = 13, color = if (state.selected == idx) hud.selInk else hud.ink, modifier = Modifier.weight(1f))
                Box(Modifier.clickable { state.selected = idx; state.deleteSelected() }) { SymText(Sym.CLOSE, size = 16, color = hud.muted) }
            }
        }
    }
}

private fun layerLabel(a: Ann): Pair<String, String> = when (a) {
    is Ann.Stroke -> if (a.highlight) Sym.INK_HIGHLIGHTER to "Highlight" else Sym.INK_PEN to "Pen"
    is Ann.ShapeAnn -> a.tool.glyph to a.tool.label
    is Ann.TextAnn -> Sym.TITLE to "Text: ${a.text.take(14)}"
    is Ann.StepAnn -> Sym.COUNTER_1 to "Step ${a.number}"
    is Ann.Redact -> Sym.BLUR_ON to "Redaction"
}

@Composable
private fun EditorCanvas(state: EditorState, toast: (String) -> Unit) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(state.bitmap) { state.bitmap.asImageBitmap() }
    val layout = remember(canvas, state.bitmap, state.padding) {
        val pad = state.padding
        val fw = state.imgW + pad * 2; val fh = state.imgH + pad * 2
        if (canvas.width == 0) Layout(1f, 0f, 0f, pad)
        else {
            val s = minOf(canvas.width / fw, canvas.height / fh) * 0.9f
            Layout(s, (canvas.width - fw * s) / 2f, (canvas.height - fh * s) / 2f, pad)
        }
    }
    var live by remember { mutableStateOf<Ann?>(null) }
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var textVal by remember { mutableStateOf(TextFieldValue("")) }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { canvas = it }
                .pointerInput(state.tool, layout, state.bitmap) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = toImage(down.position, layout)
                        val pts = mutableListOf(start)
                        var moved = false
                        var moveIdx: Int? = null
                        var lastImg = start
                        if (state.tool == Tool.SELECT) {
                            moveIdx = state.anns.indexOfLast { it.hit(start) }.takeIf { it >= 0 }
                            state.selected = moveIdx
                            if (moveIdx != null) state.beginEdit()
                        }
                        while (true) {
                            val e = awaitPointerEvent(); val ch = e.changes.firstOrNull() ?: break
                            val ip = toImage(ch.position, layout)
                            if (ch.pressed) {
                                if ((ch.position - down.position).getDistance() > 4f) moved = true
                                pts.add(ip)
                                when {
                                    state.tool == Tool.SELECT && moveIdx != null && moved -> {
                                        state.anns[moveIdx] = state.anns[moveIdx].translate(ip.x - lastImg.x, ip.y - lastImg.y); lastImg = ip
                                    }
                                    state.tool != Tool.SELECT -> live = buildAnn(state, start, ip, pts, moved)
                                }
                                ch.consume()
                            } else {
                                when (state.tool) {
                                    Tool.STEP -> if (!moved) state.add(Ann.StepAnn(start, state.stepCounter++, state.color))
                                    Tool.TEXT -> if (!moved) { textAt = start; textVal = TextFieldValue("") }
                                    Tool.CROP -> if (moved) buildRect(start, ip)?.let { state.crop(it); toast("Cropped") }
                                    Tool.SELECT -> {}
                                    else -> if (moved) buildAnn(state, start, ip, pts, true)?.let { state.add(it) }
                                }
                                live = null
                                break
                            }
                        }
                    }
                },
        ) {
            withTransform({ translate(layout.offX, layout.offY); scale(layout.scale, layout.scale, pivot = Offset.Zero) }) {
                val pad = layout.pad
                val fw = state.imgW + pad * 2; val fh = state.imgH + pad * 2
                if (state.bg != Bg.NONE) {
                    val cs = bgColors(state.bg, state.bitmap)
                    drawRect(Brush.linearGradient(listOf(Color(cs[0]), Color(cs[1])), start = Offset(0f, 0f), end = Offset(fw, fh)), size = Size(fw, fh))
                }
                translate(pad, pad) {
                    if (state.corners > 0f) {
                        val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, state.imgW.toFloat(), state.imgH.toFloat(), CornerRadius(state.corners, state.corners))) }
                        clipPath(path) { drawImage(image) }
                    } else drawImage(image)
                    state.anns.forEachIndexed { i, a -> drawAnn(a); if (state.selected == i) drawSelection(a) }
                    live?.let { drawAnn(it) }
                    if (state.tool == Tool.CROP) live?.let {}
                }
            }
        }
        // Text entry
        textAt?.let { at ->
            TextEntry(
                value = textVal, onValue = { textVal = it },
                onAdd = { if (textVal.text.isNotBlank()) state.add(Ann.TextAnn(at, textVal.text, state.color, 40f + Widths.indexOf(state.width) * 16f)); textAt = null },
                onCancel = { textAt = null },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 130.dp),
            )
        }
    }
}

@Composable
private fun TextEntry(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, onAdd: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val hud = LocalHud.current
    Row(
        modifier.background(hud.surface, RoundedCornerShape(14.dp)).border(1.dp, hud.line, RoundedCornerShape(14.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = value, onValueChange = onValue, singleLine = true,
            placeholder = { HudText("Type text…", size = 14, color = hud.muted) },
            modifier = Modifier.width(300.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = hud.track, unfocusedContainerColor = hud.track),
        )
        HudButton(Sym.CHECK, "Add", diameter = 40, color = hud.primary, onClick = onAdd)
        HudButton(Sym.CLOSE, "Cancel", diameter = 40, onClick = onCancel)
    }
}

private fun toImage(p: Offset, l: Layout): Offset = Offset((p.x - l.offX) / l.scale - l.pad, (p.y - l.offY) / l.scale - l.pad)

private fun buildRect(a: Offset, b: Offset): androidx.compose.ui.geometry.Rect? {
    val r = androidx.compose.ui.geometry.Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    return if (r.width >= 8 && r.height >= 8) r else null
}

private fun buildAnn(state: EditorState, start: Offset, cur: Offset, pts: List<Offset>, moved: Boolean): Ann? {
    if (!moved) return null
    return when (state.tool) {
        Tool.PEN -> Ann.Stroke(pts.toList(), state.color, state.width, highlight = false)
        Tool.HIGHLIGHT -> Ann.Stroke(pts.toList(), state.color, state.width, highlight = true)
        Tool.REDACT -> Ann.Redact(androidx.compose.ui.geometry.Rect(minOf(start.x, cur.x), minOf(start.y, cur.y), maxOf(start.x, cur.x), maxOf(start.y, cur.y)))
        Tool.CROP -> null
        Tool.ARROW, Tool.LINE, Tool.RECT, Tool.ELLIPSE -> Ann.ShapeAnn(state.tool, start, cur, state.color, state.width)
        else -> null
    }
}

private fun DrawScope.drawSelection(a: Ann) {
    val b = a.bounds()
    drawRect(Color(0xFF1A73E8), topLeft = Offset(b.left - 4f, b.top - 4f), size = Size(b.width + 8f, b.height + 8f), style = Stroke(width = 2f))
}

private fun DrawScope.drawAnn(a: Ann) {
    when (a) {
        is Ann.Stroke -> {
            val path = androidx.compose.ui.graphics.Path()
            a.points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, a.color.copy(alpha = if (a.highlight) 0.35f else 1f), style = Stroke(width = if (a.highlight) a.width * 2.4f else a.width, cap = StrokeCap.Round))
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
        is Ann.TextAnn -> drawContext.canvas.nativeCanvas.drawText(
            a.text, a.pos.x, a.pos.y,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = a.color.toArgb(); textSize = a.fontSize; isFakeBoldText = true },
        )
        is Ann.StepAnn -> {
            drawCircle(a.color, radius = 26f, center = a.center)
            drawContext.canvas.nativeCanvas.drawText(
                a.number.toString(), a.center.x, a.center.y + 11f,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true },
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
