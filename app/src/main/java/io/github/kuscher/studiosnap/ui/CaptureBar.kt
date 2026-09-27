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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class CaptureMode { SHOT, REC }
enum class Source(val glyph: String, val label: String) {
    AREA(io.github.kuscher.studiosnap.util.Sym.SCREENSHOT_REGION, "Area"),
    WINDOW(io.github.kuscher.studiosnap.util.Sym.SELECT_WINDOW, "Window"),
    SCREEN(io.github.kuscher.studiosnap.util.Sym.FULLSCREEN, "Screen"),
    SCROLL(io.github.kuscher.studiosnap.util.Sym.SWIPE_VERTICAL, "Scroll"),
    TEXT(io.github.kuscher.studiosnap.util.Sym.TEXT_FIELDS, "Text"),
}

private val Sym = io.github.kuscher.studiosnap.util.Sym

/** State the capture bar shows. Phase 0 keeps it local; a later phase lifts it into a controller. */
class BarState(
    mode: CaptureMode = CaptureMode.SHOT,
    source: Source = Source.AREA,
    collapsed: Boolean = false,
) {
    var mode by mutableStateOf(mode)
    var source by mutableStateOf(source)
    var collapsed by mutableStateOf(collapsed)
    var timerSeconds by mutableStateOf(0)
    val recSources = listOf(Source.SCREEN, Source.WINDOW, Source.AREA)
    val shotSources = listOf(Source.AREA, Source.WINDOW, Source.SCREEN, Source.SCROLL, Source.TEXT)
    val sources get() = if (mode == CaptureMode.REC) recSources else shotSources
}

/** A vertical divider between bar groups. */
@Composable
private fun Divider() {
    val hud = LocalHud.current
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(hud.line))
}

@Composable
private fun Label(text: String, color: Color) {
    HudText(text, size = 14, color = color, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
}

/** The primary Capture / Record button: the one filled control. */
@Composable
private fun PrimaryButton(state: BarState, enabled: Boolean, onClick: () -> Unit) {
    val hud = LocalHud.current
    val rec = state.mode == CaptureMode.REC
    val bg = if (rec) hud.rec else hud.primary
    val fg = if (rec) hud.onRec else hud.onPrimary
    Row(
        modifier = Modifier
            .padding(start = 6.dp)
            .height(48.dp)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f), CircleShape)
            .clickableIf(enabled, onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymText(if (rec) Sym.FIBER_MANUAL_RECORD else Sym.PHOTO_CAMERA, size = 22, color = fg)
        HudText(
            if (rec) "Record" else if (state.source == Source.TEXT) "Copy text" else "Capture",
            size = 14, color = fg,
        )
    }
}

/** The floating capture bar. Callbacks are hooks that later phases fill in. */
@Composable
fun CaptureBar(
    state: BarState,
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

        if (state.collapsed) {
            HudButton(
                if (state.mode == CaptureMode.REC) Sym.VIDEOCAM else Sym.PHOTO_CAMERA,
                if (state.mode == CaptureMode.REC) "Recording" else "Screenshot",
                selected = true,
                onClick = { state.mode = if (state.mode == CaptureMode.REC) CaptureMode.SHOT else CaptureMode.REC },
            )
            PrimaryButton(state, enabled = true, onClick = onPrimary)
            HudButton(Sym.CHEVRON_RIGHT, "Expand", onClick = { state.collapsed = false })
            return@Row
        }

        // Mode: Screenshot | Record
        HudTrack {
            ModeButton(state, CaptureMode.SHOT, Sym.PHOTO_CAMERA, "Screenshot")
            ModeButton(state, CaptureMode.REC, Sym.VIDEOCAM, "Record")
        }
        Divider()
        // Source
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            state.sources.forEach { src -> SourceButton(state, src) }
        }
        Divider()
        HudButton(Sym.TIMER, "Timer", selected = state.timerSeconds > 0, onClick = {
            state.timerSeconds = when (state.timerSeconds) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
        })
        HudButton(Sym.TUNE, "Options", onClick = {})
        if (state.mode == CaptureMode.SHOT) HudButton(Sym.HISTORY, "Last area", enabled = false, onClick = {})
        PrimaryButton(state, enabled = true, onClick = onPrimary)
        HudButton(Sym.CHEVRON_LEFT, "Collapse", onClick = { state.collapsed = true })
        HudButton(Sym.CLOSE, "Close", onClick = onClose)
    }
}

@Composable
private fun RowScope.ModeButton(state: BarState, mode: CaptureMode, glyph: String, label: String) {
    val hud = LocalHud.current
    val on = state.mode == mode
    Row(
        modifier = Modifier
            .height(40.dp)
            .background(if (on) hud.selBg else Color.Transparent, CircleShape)
            .clickableIf(true) {
                state.mode = mode
                if (mode == CaptureMode.REC && state.source !in state.recSources) state.source = Source.AREA
            }
            .padding(horizontal = if (on) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymText(glyph, size = 22, filled = on, color = if (on) hud.selInk else hud.ink)
        if (on) HudText(label, size = 14, color = hud.selInk)
    }
}

@Composable
private fun SourceButton(state: BarState, src: Source) {
    val hud = LocalHud.current
    val on = state.source == src
    Row(
        modifier = Modifier
            .height(44.dp)
            .background(if (on) hud.selBg else Color.Transparent, CircleShape)
            .clickableIf(true) { state.source = src }
            .padding(horizontal = if (on) 12.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymText(src.glyph, size = 22, filled = on, color = if (on) hud.selInk else hud.ink)
        if (on) HudText(src.label, size = 14, color = hud.selInk)
    }
}

/** A [Modifier.clickable] that does nothing when disabled. */
private fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, onClick = onClick)
