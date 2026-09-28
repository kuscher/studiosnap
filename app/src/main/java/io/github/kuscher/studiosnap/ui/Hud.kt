package io.github.kuscher.studiosnap.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText

/** StudioSnap's frosted-HUD palette. Neutral surface, one coral accent, fixed record red. */
@Immutable
data class HudColors(
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val hover: Color,
    val track: Color,
    val selBg: Color,
    val selInk: Color,
    val primary: Color,
    val onPrimary: Color,
    val rec: Color,
    val onRec: Color,
    val dark: Boolean,
) {
    companion object {
        val Light = HudColors(
            surface = Color(0xF2FAFAFC),
            ink = Color(0xFF1B1D22),
            muted = Color(0xFF5E6570),
            line = Color(0x1F14181F),
            hover = Color(0x1214181F),
            track = Color(0x0F14181F),
            selBg = Color(0xFFFFDDD3),
            selInk = Color(0xFF8A2710),
            primary = Color(0xFFC23B1A),
            onPrimary = Color(0xFFFFFFFF),
            rec = Color(0xFFD32F2F),
            onRec = Color(0xFFFFFFFF),
            dark = false,
        )
        val Dark = HudColors(
            surface = Color(0xE61E2025),
            ink = Color(0xFFEEF0F3),
            muted = Color(0xFFA3AAB5),
            line = Color(0x21FFFFFF),
            hover = Color(0x14FFFFFF),
            track = Color(0x12FFFFFF),
            selBg = Color(0xFF5C2618),
            selInk = Color(0xFFFFC3B3),
            primary = Color(0xFFFF9275),
            onPrimary = Color(0xFF2B0E06),
            rec = Color(0xFFFF6B6B),
            onRec = Color(0xFF2A0808),
            dark = true,
        )
    }
}

val LocalHud: ProvidableCompositionLocal<HudColors> = compositionLocalOf { HudColors.Light }

/** Fonts. Body is the system default (Google Sans Flex on Googlebook). Icons come from assets. */
object SsFonts {
    private var iconRegular: FontFamily? = null
    private var iconFilled: FontFamily? = null
    fun icon(ctx: Context, filled: Boolean): FontFamily {
        val assets = ctx.applicationContext.assets
        return if (filled) {
            iconFilled ?: FontFamily(Font("fonts/MaterialSymbolsRounded_Fill.ttf", assets)).also { iconFilled = it }
        } else {
            iconRegular ?: FontFamily(Font("fonts/MaterialSymbolsRounded.ttf", assets)).also { iconRegular = it }
        }
    }
}

/** Renders one Material Symbol by its codepoint string (see [io.github.kuscher.studiosnap.util.Sym]). */
@Composable
fun SymText(
    glyph: String,
    modifier: Modifier = Modifier,
    size: Int = 22,
    filled: Boolean = false,
    color: Color = LocalHud.current.ink,
) {
    val ctx = LocalContext.current
    val family = remember(filled) { SsFonts.icon(ctx, filled) }
    BasicText(
        text = glyph,
        modifier = modifier,
        style = TextStyle(fontFamily = family, fontSize = size.sp, color = color),
    )
}

/** A round HUD icon button, 44 dp by default, with selected/hover states. */
@Composable
fun HudButton(
    glyph: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    diameter: Int = 44,
    iconSize: Int = 22,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val hud = LocalHud.current
    val bg = when {
        selected -> hud.selBg
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> hud.ink.copy(alpha = 0.4f)
        selected -> hud.selInk
        else -> color ?: hud.ink
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = diameter.dp, minHeight = diameter.dp)
            .size(diameter.dp)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        SymText(glyph, size = iconSize, filled = selected, color = fg)
    }
}

/** A soft rounded container tinted from a HUD token; used for segmented groups. */
@Composable
fun HudTrack(modifier: Modifier = Modifier, radius: Int = 24, content: @Composable RowScope.() -> Unit) {
    val hud = LocalHud.current
    Row(
        modifier = modifier
            .background(hud.track, RoundedCornerShape(radius.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Convenience for text in the HUD body face. */
@Composable
fun HudText(text: String, modifier: Modifier = Modifier, size: Int = 14, weight: FontWeight = FontWeight.Medium, color: Color = LocalHud.current.ink) {
    BasicText(text = text, modifier = modifier, style = TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight, color = color))
}

@Composable
fun ProvideHud(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHud provides if (dark) HudColors.Dark else HudColors.Light, content = content)
}
