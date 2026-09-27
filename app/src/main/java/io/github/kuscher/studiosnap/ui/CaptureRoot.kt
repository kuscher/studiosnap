package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.capture.CaptureSession

/** The capture overlay: frozen backdrop + selection layer, with the floating bar docked above the taskbar. */
@Composable
fun CaptureRoot(session: CaptureSession, dark: Boolean = isSystemInDarkTheme()) {
    ProvideHud(dark) {
        Box(Modifier.fillMaxSize()) {
            SelectionLayer(session, adjustBeforeCapture = false)
            Box(Modifier.fillMaxSize().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
                CaptureBar(
                    session = session,
                    onPrimary = { session.primary() },
                    onClose = { session.cancel() },
                )
            }
        }
    }
}
