package io.github.kuscher.studiosnap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kuscher.studiosnap.util.Sym

/**
 * First-run onboarding. Sells what StudioSnap does, then walks the user through enabling the
 * accessibility service (the app can't toggle it — that needs WRITE_SECURE_SETTINGS). The caller
 * re-checks [serviceOn] on resume, so this flips to the "all set" state the moment the user
 * returns from the settings screen with it enabled.
 */
@Composable
fun OnboardingScreen(
    serviceOn: Boolean,
    onEnable: () -> Unit,
    onStart: () -> Unit,
    onSkip: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.surface), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 480.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("StudioSnap", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                Text(
                    "Screenshot, record, and mark up anything on your Googlebook.",
                    fontSize = 16.sp, color = scheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Feature(Sym.PHOTO_CAMERA, "Capture anything", "Area, window, full screen, or a single UI element — with a loupe and colour picker.")
                Feature(Sym.VIDEOCAM, "Record the screen", "Save an MP4 to your gallery in a tap.")
                Feature(Sym.EDIT, "Mark it up", "Arrows, text, blur, step badges — plus one-tap frames that make it look good.")
                Feature(Sym.TEXT_FIELDS, "Grab the text", "Copy real, selectable text straight out of any window.")
            }

            AnimatedContent(targetState = serviceOn, label = "onboard-cta") { ready ->
                if (ready) ReadyCard(onStart) else EnableCard(onEnable)
            }

            if (!serviceOn) {
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Skip for now", color = scheme.onSurfaceVariant)
                }
            }
            Text(
                "No internet permission — captures never leave your device.",
                fontSize = 12.sp, color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Feature(glyph: String, title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { SymText(glyph, size = 24, filled = true, color = scheme.onPrimaryContainer) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text(body, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EnableCard(onEnable: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceVariant).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SymText(Sym.BOLT, size = 22, filled = true, color = scheme.primary)
            Text("One-time setup", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        }
        Text(
            "StudioSnap watches for the Screenshot key and grabs the screen through Android's " +
                "accessibility service. Turn it on to capture with a single keypress.",
            fontSize = 13.sp, color = scheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Step(1, "Open Accessibility settings below")
            Step(2, "Tap StudioSnap under installed services")
            Step(3, "Toggle it on, then come back here")
        }
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
            SymText(Sym.BOLT, size = 18, filled = true, color = scheme.onPrimary)
            Spacer(Modifier.size(8.dp))
            Text("Turn on StudioSnap")
        }
    }
}

@Composable
private fun ReadyCard(onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val green = Color(0xFF1E8E4E)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(green.copy(alpha = 0.12f)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SymText(Sym.CHECK_CIRCLE, size = 24, filled = true, color = green)
            Text("You're all set", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SymText(Sym.KEYBOARD, size = 22, color = scheme.onSurfaceVariant)
            Text(
                "Press the Screenshot key — or Action + Shift + S — anywhere to capture.",
                fontSize = 13.sp, color = scheme.onSurfaceVariant,
            )
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start capturing") }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = scheme.onPrimary) }
        Text(text, fontSize = 13.sp, color = scheme.onSurface)
    }
}
