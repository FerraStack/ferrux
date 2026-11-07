// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Chrome colours derived from the default "Ferrux Night" terminal palette
 * (crates/ferrux-term/src/color.rs, documented in the README).
 */
object TerminalColors {
    /** Terminal default background; also behind the transparent system bars. */
    val Background = Color(0xFF0E1116)
    val Foreground = Color(0xFFD6DCE5)

    /** Ferrux orange: the cursor colour, also used for active toggles. */
    val Accent = Color(0xFFF29E4C)
    val OnAccent = Color(0xFF1A0F05)

    /** Slightly lighter than the terminal so the row reads as a separate surface. */
    val ExtraKeysBackground = Color(0xFF171B22)
    val KeyPressed = Color(0xFF2A303B)
    val KeyLabel = Color(0xFFC9D1DC)
}

private val FerruxColors = darkColorScheme(
    primary = TerminalColors.Accent,
    onPrimary = TerminalColors.OnAccent,
    background = TerminalColors.Background,
    onBackground = TerminalColors.Foreground,
    surface = Color(0xFF151920),
    onSurface = TerminalColors.Foreground,
    surfaceContainer = TerminalColors.ExtraKeysBackground,
    surfaceContainerLow = Color(0xFF151920),
    secondaryContainer = Color(0xFF3A2A1C),
    onSecondaryContainer = Color(0xFFFFDCC2),
)

@Composable
fun FerruxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FerruxColors, content = content)
}
