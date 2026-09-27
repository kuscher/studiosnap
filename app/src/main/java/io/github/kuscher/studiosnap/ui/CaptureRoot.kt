package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The content shown in the capture overlay: the floating bar, docked above the taskbar in a
 * full-screen overlay. Later phases add the selection layer, result cards and pins to this Box.
 */
@Composable
fun CaptureRoot(
    dark: Boolean = isSystemInDarkTheme(),
    onPrimary: (BarState) -> Unit,
    onClose: () -> Unit,
) {
    val state = remember { BarState() }
    ProvideHud(dark) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            CaptureBar(
                state = state,
                onPrimary = { onPrimary(state) },
                onClose = onClose,
                modifier = Modifier.padding(bottom = 72.dp),
            )
        }
    }
}
