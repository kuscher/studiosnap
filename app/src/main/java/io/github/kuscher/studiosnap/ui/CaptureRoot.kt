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

/** Where the bar sits in the full-screen capture overlay (dp): the service places messages by it. */
const val BAR_BOTTOM_GAP_DP = 72
const val BAR_TOP_GAP_DP = 52

/** The capture overlay: frozen backdrop + selection layer, with the floating bar docked above the taskbar. */
@Composable
fun CaptureRoot(session: CaptureSession, dark: Boolean = isSystemInDarkTheme(), barAtTop: Boolean = false) {
    ProvideHud(dark) {
        Box(Modifier.fillMaxSize()) {
            SelectionLayer(session, adjustBeforeCapture = false)
            val align = if (barAtTop) Alignment.TopCenter else Alignment.BottomCenter
            val pad = if (barAtTop) Modifier.fillMaxSize().padding(top = BAR_TOP_GAP_DP.dp) else Modifier.fillMaxSize().padding(bottom = BAR_BOTTOM_GAP_DP.dp)
            Box(pad, contentAlignment = align) {
                CaptureBar(
                    session = session,
                    onPrimary = { session.primary() },
                    onClose = { session.cancel() },
                )
            }
        }
    }
}
