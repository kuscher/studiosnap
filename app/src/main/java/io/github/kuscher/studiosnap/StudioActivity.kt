package io.github.kuscher.studiosnap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.kuscher.studiosnap.studio.EditorState
import io.github.kuscher.studiosnap.studio.StudioScreen

/** The annotation editor. Opens a capture from a working-file path or an EDIT/VIEW image intent. */
class StudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bmp = loadBitmap()
        if (bmp == null) { finish(); return }
        val state = EditorState(bmp)
        if (intent?.getBooleanExtra(EXTRA_DEMO, false) == true) applyDemo(state)
        setContent {
            StudioScreen(state, dark = isSystemInDarkTheme(), onClose = { finish() })
        }
    }

    /** Pre-populates a frame + a few annotations, for visual checks only. */
    private fun applyDemo(s: io.github.kuscher.studiosnap.studio.EditorState) {
        s.bg = io.github.kuscher.studiosnap.studio.Bg.SKY
        s.padding = s.imgW * 0.06f
        s.corners = 26f
        val w = s.imgW.toFloat(); val h = s.imgH.toFloat()
        s.add(io.github.kuscher.studiosnap.studio.Ann.ShapeAnn(io.github.kuscher.studiosnap.studio.Tool.ARROW, androidx.compose.ui.geometry.Offset(w * 0.62f, h * 0.28f), androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.5f), androidx.compose.ui.graphics.Color(0xFFE4502B), 10f))
        s.add(io.github.kuscher.studiosnap.studio.Ann.StepAnn(androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.5f), 1, androidx.compose.ui.graphics.Color(0xFFE4502B)))
        s.add(io.github.kuscher.studiosnap.studio.Ann.Redact(androidx.compose.ui.geometry.Rect(w * 0.15f, h * 0.68f, w * 0.5f, h * 0.76f)))
        s.add(io.github.kuscher.studiosnap.studio.Ann.TextAnn(androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.2f), "Look here", androidx.compose.ui.graphics.Color(0xFF1A73E8), 54f))
    }

    private fun loadBitmap(): Bitmap? {
        val opts = BitmapFactory.Options().apply { inMutable = false }
        intent?.getStringExtra(EXTRA_PATH)?.let { return BitmapFactory.decodeFile(it, opts) }
        val data = intent?.data ?: return null
        return runCatching {
            contentResolver.openInputStream(data)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    companion object { const val EXTRA_PATH = "path"; const val EXTRA_DEMO = "demo" }
}
