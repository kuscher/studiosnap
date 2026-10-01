package io.github.kuscher.studiosnap.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kuscher.studiosnap.isServiceEnabled
import io.github.kuscher.studiosnap.util.NotificationAccess
import io.github.kuscher.studiosnap.util.Sym

/** What StudioSnap may use besides its accessibility service, read when a screen resumes. */
data class Permissions(val service: Boolean, val notifications: Boolean, val mic: Boolean, val camera: Boolean) {
    companion object {
        fun read(ctx: Context) = Permissions(
            service = isServiceEnabled(ctx),
            notifications = NotificationAccess.isOn(ctx),
            mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            camera = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
}

/**
 * What StudioSnap may use, and why each one is there. [onTurnOnService] adds the accessibility
 * service's row (required; Settings shows it, while first run and home have their own card for it).
 * Notifications are
 * recommended and asked for here (and once before the first recording). The microphone and camera are only
 * explained: Record mode asks for them the first time their toggle is turned on, which is when the
 * reason is obvious. Shown on first run, on the home screen while notifications are off, and in
 * Settings.
 */
@Composable
fun PermissionsCard(
    state: Permissions,
    onAllowNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    onTurnOnService: (() -> Unit)? = null,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (onTurnOnService != null) ServiceRow(state.service, onTurnOnService)
            NotificationsRow(state.notifications, onAllowNotifications)
            PermissionRow(
                glyph = Sym.MIC, title = "Microphone", tier = "When you use it",
                body = "For voice-overs and app sound. Asked the first time you turn on the mic or system audio for a recording.",
                on = state.mic,
            )
            PermissionRow(
                glyph = Sym.VIDEO_CAMERA_FRONT, title = "Camera", tier = "When you use it",
                body = "For the camera bubble. Asked the first time you turn it on for a recording.",
                on = state.camera,
            )
        }
    }
}

/** The accessibility service: required, since it's how StudioSnap sees the key and the screen. */
@Composable
internal fun ServiceRow(on: Boolean, onTurnOn: () -> Unit) = PermissionRow(
    glyph = Sym.BOLT, title = "Accessibility service", tier = "Required",
    body = "Open Accessibility › StudioSnap › turn it on. It watches for the Screenshot key and grabs the screen.",
    on = on, onAllow = onTurnOn, allowLabel = "Turn on",
)

/** Notifications: recommended. Asked for here, or once before the first recording if not allowed by then. */
@Composable
internal fun NotificationsRow(on: Boolean, onAllow: () -> Unit) = PermissionRow(
    glyph = Sym.NOTIFICATIONS, title = "Notifications", tier = "Recommended",
    body = "Shows the recording notification, with a Stop button, while StudioSnap records.",
    on = on, onAllow = onAllow,
)

@Composable
private fun PermissionRow(
    glyph: String, title: String, tier: String, body: String, on: Boolean,
    onAllow: (() -> Unit)? = null, allowLabel: String = "Allow",
) {
    val scheme = MaterialTheme.colorScheme
    val green = Color(0xFF1E8E4E)
    Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { SymText(glyph, size = 22, filled = true, color = scheme.onPrimaryContainer) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                Text(tier, fontSize = 12.sp, color = scheme.primary)
            }
            Text(body, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
        when {
            on -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SymText(Sym.CHECK_CIRCLE, size = 18, filled = true, color = green)
                Text("Allowed", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
            onAllow != null -> FilledTonalButton(onClick = onAllow) { Text(allowLabel) }
            else -> Text("Not yet", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
    }
}
