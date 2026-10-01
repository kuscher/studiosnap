package io.github.kuscher.studiosnap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kuscher.studiosnap.util.Settings
import io.github.kuscher.studiosnap.ui.Permissions
import io.github.kuscher.studiosnap.ui.AccessibilityDisclosure
import io.github.kuscher.studiosnap.ui.PermissionsCard
import io.github.kuscher.studiosnap.util.NotificationAccess

class SettingsActivity : ComponentActivity() {
    private val permissions = mutableStateOf(Permissions(service = false, notifications = false, mic = false, camera = false))
    private lateinit var notifications: NotificationAccess

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        notifications = NotificationAccess(this) { permissions.value = Permissions.read(this) }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    // Play's disclosure first; only Agree opens Accessibility settings.
                    var disclose by remember { mutableStateOf(false) }
                    if (disclose) {
                        AccessibilityDisclosure(
                            onAgree = {
                                disclose = false
                                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onCancel = { disclose = false },
                        )
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                        Section("Shortcuts")
                        Toggle("Take over the Screenshot key", "StudioSnap opens instead of the system tool. It also sees the keys you press, so keep this off if you'd rather bind Action+Shift+S yourself.", s.keyTakeover) { s.keyTakeover = it }

                        Section("After a capture")
                        Toggle("Copy to the clipboard", null, s.copyAfter) { s.copyAfter = it }
                        Toggle("Save to Pictures/StudioSnap", null, s.saveAfter) { s.saveAfter = it }
                        Toggle("Show the result card", null, s.showCard) { s.showCard = it }

                        Section("Capture bar")
                        Toggle("Dock the bar at the top", "Otherwise it floats above the taskbar.", s.barAtTop) { s.barAtTop = it }

                        Section("Permissions")
                        PermissionsCard(
                            permissions.value, notifications::request, Modifier.padding(top = 6.dp),
                            onTurnOnService = { disclose = true },
                        )

                        Section("About")
                        Text("StudioSnap — a capture studio for Googlebook. Open source (MIT). No internet permission; everything stays on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissions.value = Permissions.read(this)
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
    }
}
