package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.record.RecordingBus
import io.github.kuscher.studiosnap.util.Sym

/**
 * The small pill shown while recording: time, stop, discard. Its window is sized to the pill and
 * placed top-centre by the service, so the screen around it stays clickable.
 */
@Composable
fun RecordRoot(dark: Boolean) {
    ProvideHud(dark) {
        val hud = LocalHud.current
        val ms by RecordingBus::elapsedMs
        Box(contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.height(52.dp).background(hud.surface, CircleShape).border(1.dp, hud.line, CircleShape).padding(start = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.padding(start = 8.dp, end = 4.dp).size(11.dp).background(hud.rec, CircleShape))
                HudText(fmt(ms), size = 15, color = hud.ink)
                Box(Modifier.width(6.dp))
                Row(
                    Modifier.height(40.dp).background(hud.rec, CircleShape).clickable { RecordingBus.controller?.stop() }.padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) { SymText(Sym.STOP, size = 20, color = hud.onRec); HudText("Stop", size = 14, color = hud.onRec) }
                HudButton(Sym.DELETE, "Discard", diameter = 40, onClick = { RecordingBus.controller?.discard() })
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
