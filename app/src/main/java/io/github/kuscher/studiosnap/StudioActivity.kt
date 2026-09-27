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
        setContent {
            StudioScreen(EditorState(bmp), dark = isSystemInDarkTheme(), onClose = { finish() })
        }
    }

    private fun loadBitmap(): Bitmap? {
        val opts = BitmapFactory.Options().apply { inMutable = false }
        intent?.getStringExtra(EXTRA_PATH)?.let { return BitmapFactory.decodeFile(it, opts) }
        val data = intent?.data ?: return null
        return runCatching {
            contentResolver.openInputStream(data)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    companion object { const val EXTRA_PATH = "path" }
}
