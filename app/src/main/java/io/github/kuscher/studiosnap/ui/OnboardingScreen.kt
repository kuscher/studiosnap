package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kuscher.studiosnap.util.Sym

private data class Feature(val glyph: String, val title: String, val body: String)

private val features = listOf(
    Feature(Sym.PHOTO_CAMERA, "Capture anything", "Area, window, full screen, or a single UI element."),
    Feature(Sym.VIDEOCAM, "Record the screen", "Save an MP4 to your gallery in a tap."),
    Feature(Sym.EDIT, "Mark it up", "Arrows, text, blur, step badges, one-tap frames."),
    Feature(Sym.TEXT_FIELDS, "Grab the text", "Copy real, selectable text from any window."),
)

/**
 * First-run welcome, laid out for a desktop-class window: a two-column hero (copy + setup on the
 * left, a live product preview on the right) that stacks on narrow windows, sized to fit the
 * window a Googlebook opens it in without scrolling. Re-checks [serviceOn] on resume, so it flips
 * to "all set" the moment the service is enabled.
 */
@Composable
fun OnboardingScreen(
    serviceOn: Boolean,
    permissions: Permissions,
    onAllowNotifications: () -> Unit,
    onEnable: () -> Unit,
    onStart: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    BoxWithConstraints(
        Modifier.fillMaxSize().background(scheme.surface).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 900.dp
        val content: @Composable () -> Unit = {
            Column(
                Modifier.widthIn(max = 1160.dp).fillMaxWidth().padding(horizontal = 48.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        LeftPane(serviceOn, permissions, onAllowNotifications, onEnable, onStart, Modifier.weight(1f))
                        Column(Modifier.weight(1.08f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            HeroPreview(dark, Modifier.fillMaxWidth().aspectRatio(1.42f))
                            KeyHint()
                        }
                    }
                } else {
                    HeroPreview(dark, Modifier.fillMaxWidth().aspectRatio(1.6f))
                    KeyHint()
                    LeftPane(serviceOn, permissions, onAllowNotifications, onEnable, onStart, Modifier.fillMaxWidth())
                }
            }
        }
        ProvideTextStyle(LocalTextStyle.current.merge(ReadableText)) { content() }
    }
}

@Composable
private fun LeftPane(
    serviceOn: Boolean,
    permissions: Permissions,
    onAllowNotifications: () -> Unit,
    onEnable: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) { SymText(Sym.PHOTO_CAMERA, size = 28, filled = true, color = scheme.onPrimary) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("StudioSnap", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                Text("Screenshot, record, and mark up anything on your Googlebook.", fontSize = 15.sp, color = scheme.onSurfaceVariant)
            }
        }

        // Two by two, so the setup card fits in the window without scrolling.
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            features.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { f -> FeatureItem(f, Modifier.weight(1f)) }
                }
            }
        }

        SetupCard(serviceOn, permissions, onAllowNotifications, onEnable, onStart)
    }
}

/** How to capture once set up: the caption under the preview. */
@Composable
private fun KeyHint() {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        SymText(Sym.KEYBOARD, size = 20, color = scheme.onSurfaceVariant)
        Text("Press the Screenshot key, or Action + Shift + S, anywhere.", fontSize = 13.sp, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun FeatureItem(f: Feature, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { SymText(f.glyph, size = 22, filled = true, color = scheme.onPrimaryContainer) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text(f.body, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * Everything to set up, in one card: the accessibility service (required), notifications
 * (recommended), and a line on the microphone and camera, which Record mode asks for when they're
 * first turned on. Turn on shows Google Play's disclosure first ([AccessibilityDisclosure]). Start
 * capturing is always there, disabled until the service is on (no skip: the editor has its own
 * launcher entry).
 */
@Composable
private fun SetupCard(
    serviceOn: Boolean,
    permissions: Permissions,
    onAllowNotifications: () -> Unit,
    onEnable: () -> Unit,
    onStart: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val green = Color(0xFF1E8E4E)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (serviceOn) SymText(Sym.CHECK_CIRCLE, size = 24, filled = true, color = green)
                else SymText(Sym.BOLT, size = 22, filled = true, color = scheme.primary)
                Text(if (serviceOn) "You're all set" else "One-time setup", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            }
            ServiceRow(serviceOn, onEnable)
            NotificationsRow(permissions.notifications, onAllowNotifications)
            Text(
                "Microphone and camera access is only requested if you turn them on for a recording. " +
                    "No internet permission: captures never leave your device.",
                fontSize = 13.sp, color = scheme.onSurfaceVariant,
            )
            Button(onClick = onStart, enabled = serviceOn, modifier = Modifier.fillMaxWidth()) { Text("Start capturing") }
        }
    }
}

// ---- product preview ("screenshot of the UX") ----

/** A live mock of the app in use: a captured window with an annotation, and the floating bar. */
@Composable
private fun HeroPreview(dark: Boolean, modifier: Modifier) {
    val coral = Color(0xFFE4502B)
    val deskTop = if (dark) listOf(Color(0xFF3A2A40), Color(0xFF402A2A), Color(0xFF243A34))
    else listOf(Color(0xFF9CC0FF), Color(0xFFFFC3AD), Color(0xFF98E0C4))
    val winBg = if (dark) Color(0xFF242024) else Color(0xFFFDFDFF)
    val winLine = if (dark) Color(0x24FFFFFF) else Color(0x14000000)
    val barBg = if (dark) Color(0xF01F2025) else Color(0xF6FBFBFD)
    val ink = if (dark) Color(0xFFEEF0F3) else Color(0xFF20242B)
    val muted = if (dark) Color(0x66FFFFFF) else Color(0x22000000)

    Box(
        modifier.clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(deskTop))
            .border(1.dp, winLine, RoundedCornerShape(22.dp)),
    ) {
        // The "captured" window, floating on the desktop.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth(0.82f).fillMaxHeight(0.66f)
                .padding(top = 26.dp).clip(RoundedCornerShape(13.dp)).background(winBg)
                .border(1.dp, winLine, RoundedCornerShape(13.dp)),
        ) {
            // title bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { Box(Modifier.size(9.dp).clip(CircleShape).background(muted)) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.height(8.dp).fillMaxWidth(0.32f).clip(CircleShape).background(muted))
            }
            // content + annotation
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    // placeholder text rows
                    fun bar(fx: Float, fy: Float, fw: Float) = drawRoundRect(
                        muted, topLeft = Offset(0f, fy * h), size = Size(fw * w, 9f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f),
                    )
                    bar(0f, 0.08f, 0.55f); bar(0f, 0.22f, 0.85f); bar(0f, 0.36f, 0.72f)
                    // highlighted element (the annotation target)
                    val hl = androidx.compose.ui.geometry.Rect(0.06f * w, 0.55f * h, 0.62f * w, 0.8f * h)
                    val cr = androidx.compose.ui.geometry.CornerRadius(14f, 14f)
                    drawRoundRect(coral.copy(alpha = 0.12f), topLeft = hl.topLeft, size = hl.size, cornerRadius = cr)
                    drawRoundRect(coral, topLeft = hl.topLeft, size = hl.size, cornerRadius = cr, style = Stroke(width = 2.5f))
                    // arrow pointing to the highlight
                    val a = Offset(0.9f * w, 0.34f * h); val b = Offset(0.66f * w, 0.6f * h)
                    drawLine(coral, a, b, strokeWidth = 4f, cap = StrokeCap.Round)
                    val dir = (a - b); val len = dir.getDistance().coerceAtLeast(1f)
                    val ux = dir.x / len; val uy = dir.y / len
                    val head = 15f
                    drawLine(coral, b, Offset(b.x + (ux * 0.5f - uy) * head, b.y + (uy * 0.5f + ux) * head), strokeWidth = 4f, cap = StrokeCap.Round)
                    drawLine(coral, b, Offset(b.x + (ux * 0.5f + uy) * head, b.y + (uy * 0.5f - ux) * head), strokeWidth = 4f, cap = StrokeCap.Round)
                }
                // step "1" badge near the arrow tail
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 4.dp)
                        .size(24.dp).clip(CircleShape).background(coral),
                    contentAlignment = Alignment.Center,
                ) { Text("1", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }

        // The floating capture bar.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                .clip(CircleShape).background(barBg).border(1.dp, winLine, CircleShape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            SymText(Sym.PHOTO_CAMERA, size = 17, color = ink)
            Row(
                Modifier.clip(CircleShape).background(coral.copy(alpha = if (dark) 0.28f else 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SymText(Sym.SCREENSHOT_REGION, size = 15, color = coral)
                Text("Area", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = coral)
            }
            SymText(Sym.SELECT_WINDOW, size = 17, color = ink.copy(alpha = 0.6f))
            SymText(Sym.FULLSCREEN, size = 17, color = ink.copy(alpha = 0.6f))
            Row(
                Modifier.clip(CircleShape).background(coral).padding(horizontal = 11.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SymText(Sym.PHOTO_CAMERA, size = 15, color = Color.White)
                Text("Capture", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
