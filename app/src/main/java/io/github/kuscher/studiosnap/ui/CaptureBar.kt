package io.github.kuscher.studiosnap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kuscher.studiosnap.capture.CaptureSession
import io.github.kuscher.studiosnap.util.Sym

enum class CaptureMode { SHOT, REC }
enum class Source(val glyph: String, val label: String) {
    AREA(Sym.SCREENSHOT_REGION, "Area"),
    WINDOW(Sym.SELECT_WINDOW, "Window"),
    SCREEN(Sym.FULLSCREEN, "Screen"),
    SCROLL(Sym.SWIPE_VERTICAL, "Scroll"),
    TEXT(Sym.TEXT_FIELDS, "Text"),
}

@Composable
private fun Divider() {
    val hud = LocalHud.current
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(hud.line))
}

/** The primary Capture / Record button: the one filled control. */
@Composable
private fun PrimaryButton(session: CaptureSession, enabled: Boolean, onClick: () -> Unit) {
    val hud = LocalHud.current
    val rec = session.mode == CaptureMode.REC
    val bg = if (rec) hud.rec else hud.primary
    val fg = if (rec) hud.onRec else hud.onPrimary
    Row(
        modifier = Modifier
            .padding(start = 6.dp)
            .height(48.dp)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymText(if (rec) Sym.FIBER_MANUAL_RECORD else Sym.PHOTO_CAMERA, size = 22, color = fg)
        HudText(if (rec) "Record" else if (session.source == Source.TEXT) "Copy text" else "Capture", size = 14, color = fg)
    }
}

/** The floating capture bar, driven by the shared [CaptureSession]. */
@Composable
fun CaptureBar(
    session: CaptureSession,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hud = LocalHud.current
    Row(
        modifier = modifier
            .shadow(16.dp, CircleShape, clip = false)
            .background(hud.surface, CircleShape)
            .border(1.dp, hud.line, CircleShape)
            .height(64.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HudButton(Sym.DRAG_INDICATOR, "Move bar", diameter = 28, iconSize = 20, color = hud.muted, onClick = {})

        if (session.collapsed) {
            HudButton(
                if (session.mode == CaptureMode.REC) Sym.VIDEOCAM else Sym.PHOTO_CAMERA,
                if (session.mode == CaptureMode.REC) "Recording" else "Screenshot",
                selected = true,
                onClick = { session.changeMode(if (session.mode == CaptureMode.REC) CaptureMode.SHOT else CaptureMode.REC) },
            )
            PrimaryButton(session, enabled = session.primaryEnabled, onClick = onPrimary)
            HudButton(Sym.CHEVRON_RIGHT, "Expand", onClick = { session.collapsed = false })
            return@Row
        }

        HudTrack {
            ModeButton(session, CaptureMode.SHOT, Sym.PHOTO_CAMERA, "Screenshot")
            ModeButton(session, CaptureMode.REC, Sym.VIDEOCAM, "Record")
        }
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            session.sources.forEach { src -> SourceButton(session, src) }
        }
        Divider()
        HudButton(Sym.TIMER, "Timer", selected = session.timerSeconds > 0, onClick = {
            session.timerSeconds = when (session.timerSeconds) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
        })
        HudButton(Sym.TUNE, "Options", onClick = {})
        if (session.mode == CaptureMode.SHOT) HudButton(Sym.HISTORY, "Last area", enabled = false, onClick = {})
        PrimaryButton(session, enabled = session.primaryEnabled, onClick = onPrimary)
        HudButton(Sym.CHEVRON_LEFT, "Collapse", onClick = { session.collapsed = true })
        HudButton(Sym.CLOSE, "Close", onClick = onClose)
    }
}

@Composable
private fun RowScope.ModeButton(session: CaptureSession, mode: CaptureMode, glyph: String, label: String) {
    val hud = LocalHud.current
    val on = session.mode == mode
    Row(
        modifier = Modifier
            .height(40.dp)
            .background(if (on) hud.selBg else Color.Transparent, CircleShape)
            .clickable { session.changeMode(mode) }
            .padding(horizontal = if (on) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymText(glyph, size = 22, filled = on, color = if (on) hud.selInk else hud.ink)
        if (on) HudText(label, size = 14, color = hud.selInk)
    }
}

@Composable
private fun SourceButton(session: CaptureSession, src: Source) {
    val hud = LocalHud.current
    val on = session.source == src
    Row(
        modifier = Modifier
            .height(44.dp)
            .background(if (on) hud.selBg else Color.Transparent, CircleShape)
            .clickable { session.changeSource(src) }
            .padding(horizontal = if (on) 12.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymText(src.glyph, size = 22, filled = on, color = if (on) hud.selInk else hud.ink)
        if (on) HudText(src.label, size = 14, color = hud.selInk)
    }
}
