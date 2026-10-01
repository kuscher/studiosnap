package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.kuscher.studiosnap.util.Sym

/**
 * Google Play's prominent disclosure for the accessibility service: what it accesses and what
 * StudioSnap does with it, on its own (not mixed with the other permissions) and right before
 * Accessibility settings. Every Turn on (first run, home, Settings) shows it. Agree and turn on
 * opens Accessibility settings; Cancel, Back or a tap outside don't. Keep it in step with
 * a11y_description.
 */
@Composable
fun AccessibilityDisclosure(onAgree: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { SymText(Sym.BOLT, size = 24, filled = true, color = scheme.primary) },
        title = { Text("Turn on the accessibility service") },
        text = {
            // A little more leading than the welcome page: this is the one screen meant to be read closely.
            ProvideTextStyle(LocalTextStyle.current.merge(ReadableText).copy(lineHeight = 1.5.em)) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "StudioSnap captures your screen through Android's accessibility service.",
                        fontSize = 14.sp, color = scheme.onSurfaceVariant,
                    )
                    // The lead-in sits with its bullets, at the bullets' spacing.
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("With it on, StudioSnap:", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                        Bullet("checks each key press only for the Screenshot key or Action + Shift + S; other keys pass straight through and are never recorded")
                        Bullet("takes a screenshot of the screen when you press that key or use the capture bar")
                        Bullet("while you capture, reads where windows and on-screen elements are so a selection can snap to them, reads their text when you pick Text, and scrolls a window for Scroll capture")
                    }
                    Text(
                        "What it reads stays on your Googlebook: StudioSnap never collects or shares it. " +
                            "You can turn the service off in Settings › Accessibility at any time.",
                        fontSize = 14.sp, color = scheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onAgree) { Text("Agree and turn on") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Bullet(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", fontSize = 14.sp, color = scheme.onSurfaceVariant)
        Text(text, fontSize = 14.sp, color = scheme.onSurfaceVariant)
    }
}
