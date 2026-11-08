// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.ferrux.app.FerruxAppNative
import dev.ferrux.terminal.TerminalNative

/**
 * A bundled terminal colour theme. The themes are defined once, in
 * `crates/ferrux-term/src/color.rs`, and read through JNI so the app chrome and
 * the terminal grid always agree.
 */
@Immutable
data class TerminalTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selection: Color,
    val accent: Color,
    /** The 16 ANSI colours. */
    val ansi: List<Color>,
) {
    /** The colours of the UI around the terminal, derived from the theme. */
    val chrome: ChromeColors by lazy {
        ChromeColors(
            dark = dark,
            background = background,
            foreground = foreground,
            accent = accent,
            onAccent = if (accent.luminance() > 0.45f) Color(0xFF14110E) else Color.White,
            // Slightly off the terminal so the row reads as its own surface.
            extraKeysBackground = lerp(background, foreground, if (dark) 0.045f else 0.06f),
            keyPressed = lerp(background, foreground, 0.16f),
            keyLabel = lerp(foreground, background, 0.12f),
            surface = lerp(background, foreground, if (dark) 0.07f else 0.05f),
            outline = lerp(background, foreground, 0.18f),
            muted = lerp(foreground, background, 0.38f),
            error = ansi.getOrElse(1) { Color(0xFFF0717A) },
        )
    }

    companion object {
        /** Used when libferrux is missing (and as the compile-time default). */
        val Fallback = TerminalTheme(
            id = "ferrux-night",
            name = "Ferrux Night",
            dark = true,
            background = Color(0xFF0E1116),
            foreground = Color(0xFFD6DCE5),
            cursor = Color(0xFFF29E4C),
            selection = Color(0xFF264166),
            accent = Color(0xFFF29E4C),
            ansi = listOf(
                0xFF1C2029, 0xFFF0717A, 0xFF9BD68A, 0xFFEBC47A, 0xFF6CB4F5, 0xFFC79BF2, 0xFF5FD1C9, 0xFFC9D1DC,
                0xFF4E5666, 0xFFFF8E96, 0xFFB6EBA3, 0xFFF7D99B, 0xFF92CBFF, 0xFFDDB8FF, 0xFF8CE6DF, 0xFFF2F5F9,
            ).map { Color(it) },
        )

        /** All bundled themes, default first. */
        val all: List<TerminalTheme> by lazy {
            if (FerruxAppNative.loadError != null) return@lazy listOf(Fallback)
            TerminalNative.themes().mapNotNull { entry ->
                val (id, name, dark) = entry.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
                val c = TerminalNative.themeColors(id)?.takeIf { it.size >= 21 } ?: return@mapNotNull null
                TerminalTheme(
                    id = id,
                    name = name,
                    dark = dark == "1",
                    background = Color(c[0]),
                    foreground = Color(c[1]),
                    cursor = Color(c[2]),
                    selection = Color(c[3]),
                    accent = Color(c[4]),
                    ansi = (5 until 21).map { Color(c[it]) },
                )
            }.ifEmpty { listOf(Fallback) }
        }

        fun byId(id: String?): TerminalTheme = all.firstOrNull { it.id == id } ?: all.first()
    }
}

/** Colours of the app chrome (extra keys, drawer, settings) for a [TerminalTheme]. */
@Immutable
data class ChromeColors(
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val accent: Color,
    val onAccent: Color,
    val extraKeysBackground: Color,
    val keyPressed: Color,
    val keyLabel: Color,
    val surface: Color,
    val outline: Color,
    /** Secondary text. */
    val muted: Color,
    /** Palette red (index 1), for failures. */
    val error: Color,
)

val LocalChrome = staticCompositionLocalOf { TerminalTheme.Fallback.chrome }

/** The current theme's chrome colours, under the names the UI code uses. */
object TerminalColors {
    /** Terminal default background; also behind the transparent system bars. */
    val Background: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.background
    val Foreground: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.foreground

    /** The theme's accent: active toggles, switches, the selected theme. */
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.onAccent

    /** Slightly off the terminal background so the row reads as a separate surface. */
    val ExtraKeysBackground: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.extraKeysBackground
    val KeyPressed: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.keyPressed
    val KeyLabel: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.keyLabel

    /** Secondary text, e.g. an exited session in the drawer. */
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.muted

    /** Palette red (index 1), for failures. */
    val Error: Color @Composable @ReadOnlyComposable get() = LocalChrome.current.error
}

@Composable
fun FerruxTheme(theme: TerminalTheme = TerminalTheme.Fallback, content: @Composable () -> Unit) {
    val c = theme.chrome
    val scheme = if (c.dark) {
        darkColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            background = c.background,
            onBackground = c.foreground,
            surface = c.surface,
            onSurface = c.foreground,
            onSurfaceVariant = c.muted,
            surfaceContainer = c.extraKeysBackground,
            surfaceContainerLow = c.surface,
            surfaceContainerHigh = lerp(c.surface, c.foreground, 0.05f),
            secondaryContainer = lerp(c.background, c.accent, 0.22f),
            onSecondaryContainer = c.foreground,
            outline = c.outline,
            outlineVariant = lerp(c.background, c.foreground, 0.1f),
        )
    } else {
        lightColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            background = c.background,
            onBackground = c.foreground,
            surface = c.surface,
            onSurface = c.foreground,
            onSurfaceVariant = c.muted,
            surfaceContainer = c.extraKeysBackground,
            surfaceContainerLow = c.surface,
            surfaceContainerHigh = lerp(c.surface, c.foreground, 0.05f),
            secondaryContainer = lerp(c.background, c.accent, 0.18f),
            onSecondaryContainer = c.foreground,
            outline = c.outline,
            outlineVariant = lerp(c.background, c.foreground, 0.1f),
        )
    }
    CompositionLocalProvider(LocalChrome provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
