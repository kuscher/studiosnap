package io.github.kuscher.studiosnap

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.ui.Source

/**
 * "StudioSnap Capture": a no-display trampoline that opens the capture bar in one tap from the
 * taskbar, a launcher shortcut, or a Quick Settings tile. Falls back to onboarding if the
 * service is off.
 */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = SnapService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on StudioSnap in Accessibility first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val source = when (intent?.getStringExtra(EXTRA_SOURCE)) {
                "area" -> Source.AREA; "window" -> Source.WINDOW; "screen" -> Source.SCREEN
                "scroll" -> Source.SCROLL; "text" -> Source.TEXT; else -> null
            }
            svc.openBar(source)
        }
        finish()
    }

    companion object { const val EXTRA_SOURCE = "source" }
}
