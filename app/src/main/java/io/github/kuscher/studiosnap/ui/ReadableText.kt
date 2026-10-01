package io.github.kuscher.studiosnap.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Text that keeps its spacing at any font size, for the welcome page and the disclosure dialog.
 * Material's default body style has a fixed 24 sp line height and 0.5 sp tracking, so a 13 sp
 * paragraph came out nearly double-spaced, looser than the gaps between blocks. This sets the line
 * height in proportion to the size, drops the tracking, and trims the leading above the first line
 * and below the last, so a layout's spacedBy() is the gap you see.
 */
internal val ReadableText = TextStyle(
    lineHeight = 1.4.em,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)
