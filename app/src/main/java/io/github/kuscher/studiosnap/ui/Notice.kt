package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.util.Sym

/**
 * A short message from the service, in the recording pill's style: what a toast would say. The
 * service shows it in its own untouchable window, because Android drops a background app's toasts
 * while its notifications are off.
 */
@Composable
fun NoticeRoot(text: String, dark: Boolean) {
    ProvideHud(dark) {
        val hud = LocalHud.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.height(40.dp).background(hud.surface, CircleShape).border(1.dp, hud.line, CircleShape).padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SymText(Sym.INFO, size = 18, color = hud.muted)
                HudText(text, size = 14, color = hud.ink)
            }
        }
    }
}
