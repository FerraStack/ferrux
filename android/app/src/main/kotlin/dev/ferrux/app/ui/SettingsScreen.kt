// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ferrux.app.BellMode
import dev.ferrux.app.ClipboardPolicy
import dev.ferrux.app.CursorStyle
import dev.ferrux.app.ExtraKeysPreset
import dev.ferrux.app.FerruxSettings
import dev.ferrux.app.R
import kotlin.math.roundToInt

private val Mono = FontFamily(
    Font(dev.ferrux.terminal.R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(dev.ferrux.terminal.R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * The Settings screen. Every change applies at once (the whole app, this screen
 * included, recolours when a theme is picked) and is saved in [FerruxSettings].
 */
@Composable
fun SettingsScreen(settings: FerruxSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val themeId by settings.theme.collectAsStateWithLifecycle()
    val fontSize by settings.fontSizeSp.collectAsStateWithLifecycle()
    val cursor by settings.cursorStyle.collectAsStateWithLifecycle()
    val blink by settings.cursorBlink.collectAsStateWithLifecycle()
    val extraKeys by settings.extraKeys.collectAsStateWithLifecycle()
    val bell by settings.bell.collectAsStateWithLifecycle()
    val keepScreenOn by settings.keepScreenOn.collectAsStateWithLifecycle()
    val closeOnCleanExit by settings.closeOnCleanExit.collectAsStateWithLifecycle()
    val clipboard by settings.clipboardWrites.collectAsStateWithLifecycle()
    val chrome = LocalChrome.current
    val current = TerminalTheme.byId(themeId)

    Column(
        modifier
            .fillMaxSize()
            .background(chrome.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = chrome.foreground)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                color = chrome.foreground,
                modifier = Modifier.semantics { heading() },
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                Group {
                    Label(stringResource(R.string.settings_theme))
                    Spacer(Modifier.height(12.dp))
                    ThemeGrid(TerminalTheme.all, selected = current.id, onSelect = { settings.setTheme(it.id) })
                }
            }
            item {
                Group {
                    FontSize(
                        sizeSp = fontSize ?: FerruxSettings.DEFAULT_FONT_SIZE_SP,
                        theme = current,
                        onChange = { settings.setFontSize(it) },
                    )
                }
            }
            item {
                Group {
                    Label(stringResource(R.string.settings_cursor))
                    Spacer(Modifier.height(12.dp))
                    Choice(
                        options = CursorStyle.entries,
                        selected = cursor,
                        label = {
                            stringResource(
                                when (it) {
                                    CursorStyle.BLOCK -> R.string.settings_cursor_block
                                    CursorStyle.UNDERLINE -> R.string.settings_cursor_underline
                                    CursorStyle.BAR -> R.string.settings_cursor_bar
                                },
                            )
                        },
                        icon = { style, selected -> CursorGlyph(style, if (selected) chrome.accent else chrome.muted) },
                        onSelect = { settings.setCursorStyle(it) },
                    )
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = stringResource(R.string.settings_cursor_blink),
                        summary = stringResource(R.string.settings_cursor_blink_summary),
                        checked = blink,
                        onChange = { settings.setCursorBlink(it) },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_keyboard)) }
            item {
                Group {
                    Label(stringResource(R.string.settings_extra_keys))
                    Spacer(Modifier.height(12.dp))
                    Choice(
                        options = ExtraKeysPreset.entries,
                        selected = extraKeys,
                        label = {
                            stringResource(
                                when (it) {
                                    ExtraKeysPreset.TWO_ROWS -> R.string.settings_extra_keys_two_rows
                                    ExtraKeysPreset.ONE_ROW -> R.string.settings_extra_keys_one_row
                                    ExtraKeysPreset.HIDDEN -> R.string.settings_extra_keys_hidden
                                },
                            )
                        },
                        onSelect = { settings.setExtraKeys(it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    ExtraKeysPreview(ExtraKeysLayout.of(extraKeys))
                }
            }

            item { SectionHeader(stringResource(R.string.settings_behaviour)) }
            item {
                Group {
                    Label(stringResource(R.string.settings_bell))
                    Summary(stringResource(R.string.settings_bell_summary))
                    Spacer(Modifier.height(12.dp))
                    Choice(
                        options = BellMode.entries,
                        selected = bell,
                        label = {
                            stringResource(
                                when (it) {
                                    BellMode.VIBRATE -> R.string.settings_bell_vibrate
                                    BellMode.BEEP -> R.string.settings_bell_beep
                                    BellMode.SILENT -> R.string.settings_bell_silent
                                },
                            )
                        },
                        onSelect = { settings.setBell(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = chrome.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = stringResource(R.string.settings_keep_screen_on),
                        summary = stringResource(R.string.settings_keep_screen_on_summary),
                        checked = keepScreenOn,
                        onChange = { settings.setKeepScreenOn(it) },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_close_on_exit),
                        summary = stringResource(R.string.settings_close_on_exit_summary),
                        checked = closeOnCleanExit,
                        onChange = { settings.setCloseOnCleanExit(it) },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_clipboard)) }
            item {
                Group {
                    Label(stringResource(R.string.settings_osc52))
                    Summary(stringResource(R.string.settings_osc52_summary))
                    Spacer(Modifier.height(12.dp))
                    Choice(
                        options = listOf(ClipboardPolicy.ASK, ClipboardPolicy.ALLOW, ClipboardPolicy.OFF),
                        selected = clipboard,
                        label = {
                            stringResource(
                                when (it) {
                                    ClipboardPolicy.ASK -> R.string.settings_osc52_ask
                                    ClipboardPolicy.ALLOW -> R.string.settings_osc52_allow
                                    ClipboardPolicy.OFF -> R.string.settings_osc52_deny
                                },
                            )
                        },
                        onSelect = { settings.setClipboardWrites(it) },
                    )
                }
            }
            item { VersionFooter() }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalChrome.current.accent,
        modifier = Modifier
            .padding(start = 4.dp, top = 14.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

/** A rounded surface holding related settings. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    val chrome = LocalChrome.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(chrome.surface)
            .border(1.dp, chrome.outline.copy(alpha = 0.35f), GroupShape)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = LocalChrome.current.foreground)
}

@Composable
private fun Summary(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalChrome.current.muted,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** A full-width segmented choice. */
@Composable
private fun <T> Choice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    icon: (@Composable (T, Boolean) -> Unit)? = null,
) {
    val chrome = LocalChrome.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = lerp(chrome.surface, chrome.accent, 0.2f),
                    activeContentColor = chrome.foreground,
                    activeBorderColor = chrome.accent.copy(alpha = 0.7f),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = chrome.muted,
                    inactiveBorderColor = chrome.outline,
                ),
                icon = {
                    if (icon != null) icon(option, isSelected) else SegmentedButtonDefaults.Icon(isSelected)
                },
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val chrome = LocalChrome.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(title)
            Summary(summary)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = chrome.onAccent,
                checkedTrackColor = chrome.accent,
                uncheckedThumbColor = chrome.muted,
                uncheckedTrackColor = chrome.background,
                uncheckedBorderColor = chrome.outline,
            ),
        )
    }
}

// ---------------------------------------------------------------- theme previews

@Composable
private fun ThemeGrid(themes: List<TerminalTheme>, selected: String, onSelect: (TerminalTheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in themes.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (theme in row) {
                    ThemeCard(theme, theme.id == selected, { onSelect(theme) }, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A theme as a small live terminal: a prompt, `ls` colours, a diff and the cursor. */
@Composable
private fun ThemeCard(theme: TerminalTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val chrome = LocalChrome.current
    val shape = RoundedCornerShape(14.dp)
    val description = stringResource(R.string.settings_theme_description, theme.name)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) chrome.accent else chrome.outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        TerminalPreview(theme, Modifier.fillMaxWidth())
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) lerp(chrome.surface, chrome.accent, 0.14f) else chrome.surface)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    theme.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = chrome.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (theme.dark) R.string.settings_theme_dark else R.string.settings_theme_light),
                    style = MaterialTheme.typography.labelSmall,
                    color = chrome.muted,
                )
            }
            if (selected) {
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(chrome.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_check), null, tint = chrome.onAccent, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
private fun TerminalPreview(theme: TerminalTheme, modifier: Modifier = Modifier) {
    val a = theme.ansi
    val text = remember(theme) {
        buildAnnotatedString {
            fun prompt() {
                withStyle(SpanStyle(color = a[4], fontWeight = FontWeight.Bold)) { append("~") }
                withStyle(SpanStyle(color = a[2])) { append(" $ ") }
            }
            prompt()
            withStyle(SpanStyle(color = theme.foreground)) { append("ls\n") }
            withStyle(SpanStyle(color = a[12], fontWeight = FontWeight.Bold)) { append("src") }
            withStyle(SpanStyle(color = theme.foreground)) { append("  README  ") }
            withStyle(SpanStyle(color = a[2], fontWeight = FontWeight.Bold)) { append("run.sh\n") }
            prompt()
            withStyle(SpanStyle(color = theme.foreground)) { append("git diff\n") }
            withStyle(SpanStyle(color = a[1])) { append("- old ") }
            withStyle(SpanStyle(color = a[2])) { append("+ new ") }
            withStyle(SpanStyle(color = a[3])) { append("@@ ") }
            withStyle(SpanStyle(color = theme.foreground, background = theme.selection)) { append("sel") }
            append("\n")
            prompt()
            // A block cursor.
            withStyle(SpanStyle(background = theme.cursor)) { append(" ") }
        }
    }
    Column(modifier.background(theme.background).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
        Text(text, fontFamily = Mono, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 5, softWrap = false)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 1..6) {
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(a[i]))
            }
        }
    }
}

// ---------------------------------------------------------------- font size

@Composable
private fun FontSize(sizeSp: Float, theme: TerminalTheme, onChange: (Float) -> Unit) {
    val chrome = LocalChrome.current
    val rounded = sizeSp.roundToInt()
    val min = FerruxSettings.MIN_FONT_SIZE_SP
    val max = FerruxSettings.MAX_FONT_SIZE_SP
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(stringResource(R.string.settings_font_size))
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.settings_font_size_value, rounded),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = Mono,
            color = chrome.accent,
        )
    }
    Summary(stringResource(R.string.settings_font_hint))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("A", 12.sp, stringResource(R.string.settings_font_smaller), enabled = rounded > min) {
            onChange((rounded - 1f).coerceAtLeast(min))
        }
        Slider(
            value = sizeSp.coerceIn(min, max),
            onValueChange = { v -> if (v.roundToInt() != rounded) onChange(v.roundToInt().toFloat()) },
            valueRange = min..max,
            steps = (max - min).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = chrome.accent,
                activeTrackColor = chrome.accent,
                inactiveTrackColor = chrome.outline,
                activeTickColor = chrome.onAccent.copy(alpha = 0.4f),
                inactiveTickColor = chrome.muted.copy(alpha = 0.5f),
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        StepButton("A", 18.sp, stringResource(R.string.settings_font_larger), enabled = rounded < max) {
            onChange((rounded + 1f).coerceAtMost(max))
        }
    }
    // The text at the chosen size, in the terminal's colours.
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(theme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = theme.ansi[4], fontWeight = FontWeight.Bold)) { append("~") }
                withStyle(SpanStyle(color = theme.ansi[2])) { append(" $ ") }
                withStyle(SpanStyle(color = theme.foreground)) { append("echo hello") }
            },
            fontFamily = Mono,
            fontSize = rounded.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun StepButton(label: String, size: androidx.compose.ui.unit.TextUnit, description: String, enabled: Boolean, onClick: () -> Unit) {
    val chrome = LocalChrome.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            label,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            color = if (enabled) chrome.foreground else chrome.muted.copy(alpha = 0.5f),
        )
    }
}

// ---------------------------------------------------------------- cursor and keys

/** A cell with the cursor shape drawn in it. */
@Composable
private fun CursorGlyph(style: CursorStyle, color: Color) {
    Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val w = size.width
        val h = size.height
        when (style) {
            CursorStyle.BLOCK -> drawRect(color)
            CursorStyle.UNDERLINE -> drawRect(
                color,
                topLeft = androidx.compose.ui.geometry.Offset(0f, h - 2.5.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(w, 2.5.dp.toPx()),
            )
            CursorStyle.BAR -> drawRect(color, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), h))
        }
    }
}

/** The chosen extra-keys layout, as small key caps. */
@Composable
private fun ExtraKeysPreview(layout: ExtraKeysLayout) {
    val chrome = LocalChrome.current
    if (layout.rows.isEmpty()) {
        Summary(stringResource(R.string.settings_extra_keys_hidden_summary))
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(chrome.extraKeysBackground)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in layout.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (key in row) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(chrome.keyPressed.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            previewLabel(key),
                            fontFamily = Mono,
                            fontSize = 9.sp,
                            color = chrome.keyLabel,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private fun previewLabel(key: ExtraKey): String = when (key) {
    is ExtraKey.Special -> when (key.icon) {
        KeyIcon.ArrowUp -> "↑"
        KeyIcon.ArrowDown -> "↓"
        KeyIcon.ArrowLeft -> "←"
        KeyIcon.ArrowRight -> "→"
        else -> key.label
    }
    is ExtraKey.Literal -> key.label
    is ExtraKey.Toggle -> key.name
    is ExtraKey.Action -> "☰"
}

@Composable
private fun VersionFooter() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Text(
        stringResource(R.string.settings_version, version.orEmpty()),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChrome.current.muted,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private val GroupShape = RoundedCornerShape(18.dp)
