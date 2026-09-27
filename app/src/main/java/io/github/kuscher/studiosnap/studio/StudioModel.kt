package io.github.kuscher.studiosnap.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

enum class Tool(val glyph: String, val label: String) {
    SELECT(io.github.kuscher.studiosnap.util.Sym.ARROW_SELECTOR_TOOL, "Select"),
    ARROW(io.github.kuscher.studiosnap.util.Sym.ARROW_OUTWARD, "Arrow"),
    LINE(io.github.kuscher.studiosnap.util.Sym.HORIZONTAL_RULE, "Line"),
    RECT(io.github.kuscher.studiosnap.util.Sym.RECTANGLE, "Box"),
    ELLIPSE(io.github.kuscher.studiosnap.util.Sym.CIRCLE, "Ellipse"),
    PEN(io.github.kuscher.studiosnap.util.Sym.INK_PEN, "Pen"),
    HIGHLIGHT(io.github.kuscher.studiosnap.util.Sym.INK_HIGHLIGHTER, "Highlighter"),
    TEXT(io.github.kuscher.studiosnap.util.Sym.TITLE, "Text"),
    STEP(io.github.kuscher.studiosnap.util.Sym.COUNTER_1, "Step"),
    REDACT(io.github.kuscher.studiosnap.util.Sym.BLUR_ON, "Redact"),
    CROP(io.github.kuscher.studiosnap.util.Sym.CROP, "Crop"),
}

/** One annotation, in image-pixel coordinates so it stays sharp at any zoom and on export. */
sealed class Ann {
    abstract val color: Color
    abstract val width: Float

    data class Stroke(val points: List<Offset>, override val color: Color, override val width: Float, val highlight: Boolean) : Ann()
    data class ShapeAnn(val tool: Tool, val start: Offset, val end: Offset, override val color: Color, override val width: Float) : Ann()
    data class TextAnn(val pos: Offset, val text: String, override val color: Color, val fontSize: Float) : Ann() { override val width = 0f }
    data class StepAnn(val center: Offset, val number: Int, override val color: Color) : Ann() { override val width = 0f }
    data class Redact(val rect: Rect) : Ann() { override val color = Color.Black; override val width = 0f }
}

fun Ann.translate(dx: Float, dy: Float): Ann = when (this) {
    is Ann.Stroke -> copy(points = points.map { Offset(it.x + dx, it.y + dy) })
    is Ann.ShapeAnn -> copy(start = Offset(start.x + dx, start.y + dy), end = Offset(end.x + dx, end.y + dy))
    is Ann.TextAnn -> copy(pos = Offset(pos.x + dx, pos.y + dy))
    is Ann.StepAnn -> copy(center = Offset(center.x + dx, center.y + dy))
    is Ann.Redact -> copy(rect = rect.translate(dx, dy))
}

fun Ann.bounds(): Rect = when (this) {
    is Ann.Stroke -> {
        val xs = points.map { it.x }; val ys = points.map { it.y }
        Rect(xs.min(), ys.min(), xs.max(), ys.max())
    }
    is Ann.ShapeAnn -> Rect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
    is Ann.TextAnn -> Rect(pos.x, pos.y - fontSize, pos.x + fontSize * 0.6f * text.length.coerceAtLeast(1), pos.y + fontSize * 0.25f)
    is Ann.StepAnn -> Rect(center.x - 28f, center.y - 28f, center.x + 28f, center.y + 28f)
    is Ann.Redact -> rect
}

fun Ann.hit(p: Offset): Boolean = bounds().inflate(12f).contains(p)

val Palette = listOf(
    Color(0xFFE4502B), Color(0xFFF5B400), Color(0xFF1FA463), Color(0xFF1A73E8),
    Color(0xFF8E44EF), Color(0xFF1B1D22), Color(0xFFFFFFFF),
)
val Widths = listOf(4f, 8f, 14f)

/** Frame / beautify background presets (linear gradients or a wallpaper-ish blend). */
enum class Bg(val label: String) { NONE("None"), AUTO("Auto"), SUNSET("Sunset"), SKY("Sky"), MINT("Mint"), SLATE("Slate") }

/** All editor state for one open capture. */
class EditorState(initial: Bitmap) {
    var bitmap by mutableStateOf(initial)
        private set
    val imgW get() = bitmap.width
    val imgH get() = bitmap.height

    val anns: SnapshotStateList<Ann> = mutableStateListOf()
    private val undoStack = ArrayDeque<List<Ann>>()
    private val redoStack = ArrayDeque<List<Ann>>()

    var tool by mutableStateOf(Tool.ARROW)
    var color by mutableStateOf(Palette[0])
    var width by mutableStateOf(Widths[1])
    var stepCounter = 1
    var selected by mutableStateOf<Int?>(null)

    // Frame / beautify
    var bg by mutableStateOf(Bg.NONE)
    var padding by mutableStateOf(0f)   // image px
    var corners by mutableStateOf(0f)   // image px

    private fun snapshot() { undoStack.addLast(anns.toList()); redoStack.clear(); if (undoStack.size > 50) undoStack.removeFirst() }

    fun add(a: Ann) { snapshot(); anns.add(a) }
    fun replaceAt(i: Int, a: Ann) { if (i in anns.indices) anns[i] = a }
    fun beginEdit() { snapshot() }
    fun deleteSelected() { selected?.let { if (it in anns.indices) { snapshot(); anns.removeAt(it); selected = null } } }
    fun undo() { if (undoStack.isNotEmpty()) { redoStack.addLast(anns.toList()); anns.clear(); anns.addAll(undoStack.removeLast()); selected = null } }
    fun redo() { if (redoStack.isNotEmpty()) { undoStack.addLast(anns.toList()); anns.clear(); anns.addAll(redoStack.removeLast()); selected = null } }
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun crop(r: Rect) {
        val x = r.left.toInt().coerceIn(0, imgW - 1); val y = r.top.toInt().coerceIn(0, imgH - 1)
        val w = r.width.toInt().coerceIn(1, imgW - x); val h = r.height.toInt().coerceIn(1, imgH - y)
        if (w < 8 || h < 8) return
        snapshot()
        bitmap = Bitmap.createBitmap(bitmap, x, y, w, h)
        for (i in anns.indices) anns[i] = anns[i].translate(-x.toFloat(), -y.toFloat())
        selected = null
    }

    /** Flatten frame + image + annotations to a new Bitmap at full resolution. */
    fun export(): Bitmap {
        val pad = padding.toInt()
        val outW = imgW + pad * 2; val outH = imgH + pad * 2
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (bg != Bg.NONE) drawBackground(c, outW, outH, bg, bitmap)
        // rounded image
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (corners > 0f) {
            val path = Path().apply { addRoundRect(pad.toFloat(), pad.toFloat(), (pad + imgW).toFloat(), (pad + imgH).toFloat(), corners, corners, Path.Direction.CW) }
            c.save(); c.clipPath(path); c.drawBitmap(bitmap, pad.toFloat(), pad.toFloat(), null); c.restore()
        } else c.drawBitmap(bitmap, pad.toFloat(), pad.toFloat(), null)
        c.translate(pad.toFloat(), pad.toFloat())
        for (a in anns) drawAnnAndroid(c, p, a)
        return out
    }
}

private fun Rect.inflate(d: Float) = Rect(left - d, top - d, right + d, bottom + d)

fun bgColors(bg: Bg, image: Bitmap): IntArray = when (bg) {
    Bg.SUNSET -> intArrayOf(0xFFFF9A76.toInt(), 0xFFF2542D.toInt())
    Bg.SKY -> intArrayOf(0xFF8EC5FF.toInt(), 0xFF6A5CFF.toInt())
    Bg.MINT -> intArrayOf(0xFFB7F0D4.toInt(), 0xFF3BB78F.toInt())
    Bg.SLATE -> intArrayOf(0xFF2B2F36.toInt(), 0xFF4B5361.toInt())
    Bg.AUTO -> { // sample two edge pixels for a matching gradient
        val a = image.getPixel(2, 2); val b = image.getPixel(image.width - 2, image.height - 2)
        intArrayOf(a, b)
    }
    Bg.NONE -> intArrayOf(0, 0)
}

private fun drawBackground(c: Canvas, w: Int, h: Int, bg: Bg, image: Bitmap) {
    val cols = bgColors(bg, image)
    val p = Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), cols[0], cols[1], android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
}

/** Renders one annotation onto an android Canvas (used for export). */
fun drawAnnAndroid(c: Canvas, p: Paint, a: Ann) {
    p.color = a.color.toArgb()
    when (a) {
        is Ann.Stroke -> {
            p.style = Paint.Style.STROKE; p.strokeWidth = a.width; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            if (a.highlight) { p.alpha = 90; p.strokeWidth = a.width * 2.4f } else p.alpha = 255
            val path = Path()
            a.points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            c.drawPath(path, p); p.alpha = 255
        }
        is Ann.ShapeAnn -> {
            p.style = Paint.Style.STROKE; p.strokeWidth = a.width; p.strokeCap = Paint.Cap.ROUND
            when (a.tool) {
                Tool.RECT -> c.drawRect(minOf(a.start.x, a.end.x), minOf(a.start.y, a.end.y), maxOf(a.start.x, a.end.x), maxOf(a.start.y, a.end.y), p)
                Tool.ELLIPSE -> c.drawOval(minOf(a.start.x, a.end.x), minOf(a.start.y, a.end.y), maxOf(a.start.x, a.end.x), maxOf(a.start.y, a.end.y), p)
                Tool.LINE -> c.drawLine(a.start.x, a.start.y, a.end.x, a.end.y, p)
                Tool.ARROW -> drawArrowAndroid(c, p, a.start, a.end, a.width)
                else -> {}
            }
        }
        is Ann.TextAnn -> {
            p.style = Paint.Style.FILL; p.textSize = a.fontSize; p.isFakeBoldText = true
            c.drawText(a.text, a.pos.x, a.pos.y, p)
        }
        is Ann.StepAnn -> {
            p.style = Paint.Style.FILL
            c.drawCircle(a.center.x, a.center.y, 26f, p)
            p.color = android.graphics.Color.WHITE; p.textSize = 30f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
            c.drawText(a.number.toString(), a.center.x, a.center.y + 11f, p); p.textAlign = Paint.Align.LEFT
        }
        is Ann.Redact -> {
            p.style = Paint.Style.FILL; p.color = android.graphics.Color.BLACK
            c.drawRect(a.rect.left, a.rect.top, a.rect.right, a.rect.bottom, p)
        }
    }
}

private fun drawArrowAndroid(c: Canvas, p: Paint, s: Offset, e: Offset, w: Float) {
    c.drawLine(s.x, s.y, e.x, e.y, p)
    val ang = kotlin.math.atan2((e.y - s.y).toDouble(), (e.x - s.x).toDouble())
    val head = (w * 3.2f).coerceAtLeast(22f)
    val a1 = ang - Math.PI / 7; val a2 = ang + Math.PI / 7
    val path = Path()
    path.moveTo(e.x, e.y)
    path.lineTo((e.x - head * kotlin.math.cos(a1)).toFloat(), (e.y - head * kotlin.math.sin(a1)).toFloat())
    path.lineTo((e.x - head * kotlin.math.cos(a2)).toFloat(), (e.y - head * kotlin.math.sin(a2)).toFloat())
    path.close()
    val prev = p.style; p.style = Paint.Style.FILL; c.drawPath(path, p); p.style = prev
}

fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
