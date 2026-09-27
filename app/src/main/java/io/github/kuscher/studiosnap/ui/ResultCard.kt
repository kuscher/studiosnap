package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.util.Sym
import kotlinx.coroutines.delay

/** One finished capture waiting in the corner. */
class CardData(
    val id: Long,
    val image: ImageBitmap,
    val label: String,
    val copied: Boolean,
    val saved: Boolean,
    val filePath: String? = null,
)

/** The bottom-left stack of result cards. Newest on top; each fades out after a few seconds. */
@Composable
fun CardStack(
    cards: List<CardData>,
    dark: Boolean,
    onDismiss: (Long) -> Unit,
    onCopy: (CardData) -> Unit,
    onEdit: (CardData) -> Unit,
) {
    ProvideHud(dark) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cards.forEach { card ->
                    ResultCard(card, onDismiss = { onDismiss(card.id) }, onCopy = { onCopy(card) }, onEdit = { onEdit(card) })
                }
            }
        }
    }
}

@Composable
private fun ResultCard(card: CardData, onDismiss: () -> Unit, onCopy: () -> Unit, onEdit: () -> Unit) {
    val hud = LocalHud.current
    LaunchedEffect(card.id) { delay(9000); onDismiss() }
    Box(
        Modifier
            .width(272.dp)
            .shadow(14.dp, RoundedCornerShape(20.dp), clip = false)
            .background(hud.surface, RoundedCornerShape(20.dp))
            .border(1.dp, hud.line, RoundedCornerShape(20.dp))
            .padding(9.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 156.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(hud.track),
                contentAlignment = Alignment.Center,
            ) {
                Image(card.image, contentDescription = card.label, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                Row(
                    Modifier.fillMaxWidth().padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // hover would reveal these; shown always for now
                    ActionChip(Sym.CONTENT_COPY, "Copy", onCopy)
                    ActionChip(Sym.EDIT, "Annotate", onEdit)
                    Box(Modifier.weight(1f))
                    ActionChip(Sym.CLOSE, "Dismiss", onDismiss)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudText(card.label, size = 12, color = hud.muted, modifier = Modifier.weight(1f))
                if (card.copied) StatusChip(Sym.CONTENT_COPY, "Copied")
                if (card.saved) StatusChip(Sym.CHECK, "Saved")
            }
        }
    }
}

@Composable
private fun ActionChip(glyph: String, cd: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color(0xF0FFFFFF), RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
    ) {
        HudButton(glyph, cd, diameter = 38, iconSize = 20, color = Color(0xFF1B1D22), onClick = onClick)
    }
}

@Composable
private fun StatusChip(glyph: String, text: String) {
    val hud = LocalHud.current
    Row(
        Modifier.background(hud.track, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SymText(glyph, size = 14, color = hud.ink)
        HudText(text, size = 11, color = hud.ink)
    }
}
