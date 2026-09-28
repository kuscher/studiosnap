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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import io.github.kuscher.studiosnap.ui.SymText
import io.github.kuscher.studiosnap.util.Sym
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
        setTaskDescription(android.app.ActivityManager.TaskDescription("StudioSnap"))
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
                            onOpenEditor = { startActivity(Intent(this, StudioActivity::class.java)) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(
    serviceOn: Boolean,
    onOpenAccessibility: () -> Unit,
    onTestBar: () -> Unit,
    onSettings: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpen: (Uri) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StudioSnap", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onSettings) {
                        SymText(Sym.TUNE, size = 22, color = scheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Service status
            val green = Color(0xFF1E8E4E)
            val tint = if (serviceOn) green else scheme.primary
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { SymText(if (serviceOn) Sym.CHECK_CIRCLE else Sym.BOLT, size = 26, filled = true, color = tint) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (serviceOn) "Ready to capture" else "Turn on StudioSnap", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (serviceOn) "Press the Screenshot key, or Action + Shift + S, anywhere."
                                else "Enable the accessibility service to capture with a keypress.",
                                fontSize = 13.sp, color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!serviceOn) {
                        Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                            Text("Turn on StudioSnap")
                        }
                    }
                }
            }

            // Primary actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (serviceOn) {
                    FilledTonalButton(onClick = onTestBar, modifier = Modifier.weight(1f)) {
                        SymText(Sym.PHOTO_CAMERA, size = 18, color = scheme.onSecondaryContainer)
                        Text("  Capture bar")
                    }
                }
                FilledTonalButton(onClick = onOpenEditor, modifier = Modifier.weight(1f)) {
                    SymText(Sym.EDIT, size = 18, color = scheme.onSecondaryContainer)
                    Text("  Editor")
                }
            }

            // Recent
            Text("Recent", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            val recent by produceState(initialValue = emptyList<Pair<Uri, ImageBitmap>>()) { value = loadRecent(ctx) }
            if (recent.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) { Text("Your captures show up here.", color = scheme.onSurfaceVariant, fontSize = 13.sp) }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent) { (uri, bmp) ->
                        Image(
                            bmp, contentDescription = null,
                            modifier = Modifier.size(158.dp, 100.dp).clip(RoundedCornerShape(14.dp))
                                .background(scheme.surfaceVariant).clickable { onOpen(uri) },
                            contentScale = ContentScale.Crop,
                        )
                    }
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
