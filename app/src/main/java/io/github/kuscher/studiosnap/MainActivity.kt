package io.github.kuscher.studiosnap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kuscher.studiosnap.service.SnapService

/** Placeholder Library / home screen for Phase 0: shows service status and a way to test the bar. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Home(
                        serviceOn = isServiceEnabled(this),
                        onOpenSettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onTestBar = { SnapService.instance?.openBar() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Home(serviceOn: Boolean, onOpenSettings: () -> Unit, onTestBar: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(PaddingValues(24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("StudioSnap", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Phase 0 · capture foundation", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            if (serviceOn) "Instant capture is on. Press the Screenshot key (or Action+Shift+S) anywhere."
            else "Instant capture is off. Turn on StudioSnap in Accessibility to use the capture key.",
        )
        Spacer(Modifier.height(8.dp))
        if (!serviceOn) Button(onClick = onOpenSettings) { Text("Open Accessibility settings") }
        else OutlinedButton(onClick = onTestBar) { Text("Open the capture bar") }
    }
}

/** True when StudioSnap's accessibility service is enabled for the current user. */
fun isServiceEnabled(ctx: Context): Boolean {
    val expected = "${ctx.packageName}/io.github.kuscher.studiosnap.service.SnapService"
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    for (item in splitter) if (item.equals(expected, ignoreCase = true)) return true
    return false
}
