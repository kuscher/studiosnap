package io.github.kuscher.studiosnap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings as SysSettings
import android.text.TextUtils
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.ui.OnboardingScreen
import io.github.kuscher.studiosnap.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Re-checked on resume so the UI updates when the user returns from Accessibility settings.
    private val serviceOn = mutableStateOf(false)
    // Debug-only: pretend the service is off (to preview onboarding's enable state). Cosmetic.
    private var forceOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forceOff = intent?.getBooleanExtra("force_off", false) == true
        serviceOn.value = !forceOff && isServiceEnabled(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val settings = remember { Settings(this) }
                    var onboarded by remember { mutableStateOf(settings.onboardingDone) }
                    val openAccessibility = { startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    if (!onboarded) {
                        OnboardingScreen(
                            serviceOn = serviceOn.value,
                            onEnable = openAccessibility,
                            onStart = { settings.onboardingDone = true; onboarded = true },
                            onSkip = { settings.onboardingDone = true; onboarded = true },
                        )
                    } else {
                        Home(
                            serviceOn = serviceOn.value,
                            onOpenAccessibility = openAccessibility,
                            onTestBar = { SnapService.instance?.openBar() },
                            onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                            onOpen = { uri -> startActivity(Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceOn.value = !forceOff && isServiceEnabled(this)
    }
}

@Composable
private fun Home(
    serviceOn: Boolean,
    onOpenAccessibility: () -> Unit,
    onTestBar: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (Uri) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("StudioSnap", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            if (serviceOn) "Ready. Press the Screenshot key, or Action+Shift+S, anywhere to capture."
            else "Turn on StudioSnap in Accessibility to capture with a keypress.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!serviceOn) Button(onClick = onOpenAccessibility) { Text("Turn on instant capture") }
            else OutlinedButton(onClick = onTestBar) { Text("Open the capture bar") }
            OutlinedButton(onClick = onSettings) { Text("Settings") }
        }

        Text("RECENT", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        val recent by produceState(initialValue = emptyList<Pair<Uri, ImageBitmap>>()) { value = loadRecent(ctx) }
        if (recent.isEmpty()) {
            Text("Your captures show up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent) { (uri, bmp) ->
                    Image(
                        bmp, contentDescription = null,
                        modifier = Modifier.size(150.dp, 96.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen(uri) },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

private suspend fun loadRecent(ctx: Context): List<Pair<Uri, ImageBitmap>> = withContext(Dispatchers.IO) {
    val out = ArrayList<Pair<Uri, ImageBitmap>>()
    runCatching {
        val proj = arrayOf(MediaStore.Images.Media._ID)
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("%StudioSnap%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < 12) {
                val id = c.getLong(0)
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                runCatching { ctx.contentResolver.loadThumbnail(uri, Size(360, 240), null) }.getOrNull()?.let {
                    out.add(uri to it.asImageBitmap()); n++
                }
            }
        }
    }
    out
}

/** True when StudioSnap's accessibility service is enabled for the current user. */
fun isServiceEnabled(ctx: Context): Boolean {
    val expected = "${ctx.packageName}/io.github.kuscher.studiosnap.service.SnapService"
    val enabled = SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    for (item in splitter) if (item.equals(expected, ignoreCase = true)) return true
    return false
}
