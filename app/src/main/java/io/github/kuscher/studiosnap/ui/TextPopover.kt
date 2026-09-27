package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.util.Sym

/** A centred popover showing text pulled from the captured region. */
@Composable
fun TextRoot(text: String, dark: Boolean, onCopy: () -> Unit, onSearch: () -> Unit, onClose: () -> Unit) {
    ProvideHud(dark) {
        Box(
            Modifier.fillMaxSize().background(Color(0x66101216)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            val hud = LocalHud.current
            Column(
                Modifier.width(460.dp).background(hud.surface, RoundedCornerShape(20.dp)).border(1.dp, hud.line, RoundedCornerShape(20.dp))
                    .clickable(enabled = false) {}.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SymText(Sym.TEXT_FIELDS, size = 20, color = hud.ink)
                    HudText("Text", size = 15, color = hud.ink)
                    Box(Modifier.weight(1f))
                    Box(Modifier.background(hud.track, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                        HudText("Exact text from the app", size = 11, color = hud.muted)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 260.dp)
                        .background(hud.track, RoundedCornerShape(12.dp)).padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    HudText(text.ifBlank { "No selectable text here. On-device OCR arrives in a later build." }, size = 14, color = hud.ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(Sym.CHECK, "Copied", selected = true) { onCopy() }
                    Chip(Sym.IMAGE_SEARCH, "Search", selected = false) { onSearch() }
                    Box(Modifier.weight(1f))
                    Chip(Sym.CLOSE, "Close", selected = false) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun Chip(glyph: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val hud = LocalHud.current
    Row(
        Modifier.background(if (selected) hud.selBg else hud.track, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymText(glyph, size = 16, color = if (selected) hud.selInk else hud.ink)
        HudText(label, size = 13, color = if (selected) hud.selInk else hud.ink)
    }
}
