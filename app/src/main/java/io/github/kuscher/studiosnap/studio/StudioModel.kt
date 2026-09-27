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

val Palette = listOf(
    Color(0xFFE4502B), Color(0xFFF5B400), Color(0xFF1FA463), Color(0xFF1A73E8),
    Color(0xFF8E44EF), Color(0xFF1B1D22), Color(0xFFFFFFFF),
)
val Widths = listOf(4f, 8f, 14f)

/** All editor state for one open capture. */
class EditorState(val image: Bitmap) {
    val imgW = image.width
    val imgH = image.height
    val anns: SnapshotStateList<Ann> = mutableStateListOf()
    private val redo = ArrayDeque<Ann>()

    var tool by mutableStateOf(Tool.ARROW)
    var color by mutableStateOf(Palette[0])
    var width by mutableStateOf(Widths[1])
    var stepCounter = 1

    fun add(a: Ann) { anns.add(a); redo.clear() }
    fun undo() { if (anns.isNotEmpty()) redo.addLast(anns.removeAt(anns.lastIndex)) }
    fun redo() { if (redo.isNotEmpty()) anns.add(redo.removeLast()) }
    val canUndo get() = anns.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Flatten the image + annotations to a new Bitmap at full resolution for copy/save. */
    fun export(): Bitmap {
        val out = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(image, 0f, 0f, null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (a in anns) drawAnnAndroid(c, p, a)
        return out
    }
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

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
