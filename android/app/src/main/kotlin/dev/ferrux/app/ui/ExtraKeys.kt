// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferrux.app.R
import dev.ferrux.terminal.StickyModifiers
import dev.ferrux.terminal.TerminalKeys
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- data model

/** App actions an extra key can trigger. */
enum class ExtraKeyAction { Drawer }

/** Built-in icons for keys whose text label would be cramped. */
enum class KeyIcon { ArrowUp, ArrowDown, ArrowLeft, ArrowRight, Menu }

/** One key of the extra-keys row. */
@Immutable
sealed interface ExtraKey {
    /** Accessible name; also what UI tests select on (content-desc). */
    val name: String

    data class Special(
        override val name: String,
        val code: Int,
        val label: String = name,
        val icon: KeyIcon? = null,
        /** Repeats while held, like a hardware key. */
        val repeatable: Boolean = false,
    ) : ExtraKey

    data class Literal(val text: String, val label: String = text) : ExtraKey {
        override val name: String get() = text
    }

    data class Toggle(override val name: String, val mod: Int) : ExtraKey

    data class Action(override val name: String, val action: ExtraKeyAction, val icon: KeyIcon) : ExtraKey
}

/**
 * Rows of extra keys. Built from Termux-style key names so a user-editable
 * JSON/TOML layout (TODO(M4)) can feed [parse] directly, e.g.
 * `[["ESC","/","-","HOME","UP","END","PGUP","DRAWER"], ["TAB","CTRL",…]]`.
 */
@Immutable
data class ExtraKeysLayout(val rows: List<List<ExtraKey>>) {
    companion object {
        /** Maps a key name to a key; anything unknown is sent as literal text. */
        fun key(name: String): ExtraKey = when (name.uppercase()) {
            "ESC" -> ExtraKey.Special("ESC", TerminalKeys.ESCAPE)
            "TAB" -> ExtraKey.Special("TAB", TerminalKeys.TAB)
            "CTRL" -> ExtraKey.Toggle("CTRL", TerminalKeys.MOD_CTRL)
            "ALT" -> ExtraKey.Toggle("ALT", TerminalKeys.MOD_ALT)
            "UP" -> ExtraKey.Special("Up", TerminalKeys.UP, icon = KeyIcon.ArrowUp, repeatable = true)
            "DOWN" -> ExtraKey.Special("Down", TerminalKeys.DOWN, icon = KeyIcon.ArrowDown, repeatable = true)
            "LEFT" -> ExtraKey.Special("Left", TerminalKeys.LEFT, icon = KeyIcon.ArrowLeft, repeatable = true)
            "RIGHT" -> ExtraKey.Special("Right", TerminalKeys.RIGHT, icon = KeyIcon.ArrowRight, repeatable = true)
            "HOME" -> ExtraKey.Special("HOME", TerminalKeys.HOME)
            "END" -> ExtraKey.Special("END", TerminalKeys.END)
            "PGUP" -> ExtraKey.Special("PGUP", TerminalKeys.PAGE_UP, repeatable = true)
            "PGDN" -> ExtraKey.Special("PGDN", TerminalKeys.PAGE_DOWN, repeatable = true)
            "DEL" -> ExtraKey.Special("DEL", TerminalKeys.DELETE, repeatable = true)
            "BKSP" -> ExtraKey.Special("BKSP", TerminalKeys.BACKSPACE, label = "⌫", repeatable = true)
            "DRAWER" -> ExtraKey.Action("Sessions", ExtraKeyAction.Drawer, KeyIcon.Menu)
            else -> ExtraKey.Literal(name)
        }

        fun parse(rows: List<List<String>>) = ExtraKeysLayout(rows.map { row -> row.map(::key) })

        val Default = parse(
            listOf(
                listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP", "DRAWER"),
                listOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN", "|"),
            ),
        )
    }
}

// ---------------------------------------------------------------- modifier state

enum class ModState { Off, Once, Locked }

/**
 * CTRL/ALT toggles. One tap arms a modifier for the next key or character
 * ([ModState.Once]); a double tap locks it until tapped again.
 */
@Stable
class StickyModifierState : StickyModifiers {
    var ctrl by mutableStateOf(ModState.Off)
    var alt by mutableStateOf(ModState.Off)
    private val lastTap = LongArray(2)

    fun state(mod: Int): ModState = if (mod == TerminalKeys.MOD_CTRL) ctrl else alt

    fun tap(mod: Int) {
        val slot = if (mod == TerminalKeys.MOD_CTRL) 0 else 1
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastTap[slot] < DOUBLE_TAP_MS
        lastTap[slot] = now
        val next = when (state(mod)) {
            ModState.Off -> ModState.Once
            ModState.Once -> if (doubleTap) ModState.Locked else ModState.Off
            ModState.Locked -> ModState.Off
        }
        if (slot == 0) ctrl = next else alt = next
    }

    override fun isActive(): Boolean = ctrl != ModState.Off || alt != ModState.Off

    override fun consume(): Int {
        var mods = 0
        if (ctrl != ModState.Off) mods = mods or TerminalKeys.MOD_CTRL
        if (alt != ModState.Off) mods = mods or TerminalKeys.MOD_ALT
        if (ctrl == ModState.Once) ctrl = ModState.Off
        if (alt == ModState.Once) alt = ModState.Off
        return mods
    }

    private companion object {
        const val DOUBLE_TAP_MS = 350L
    }
}

// ---------------------------------------------------------------- UI

private val KeyFont = FontFamily(
    Font(dev.ferrux.terminal.R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(dev.ferrux.terminal.R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * The extra-keys rows. Keys are not focusable, so tapping them never steals
 * focus (and the IME connection) from the terminal. Keys fire on press, with
 * haptic feedback; arrows and page keys repeat while held.
 */
@Composable
fun ExtraKeysRow(
    layout: ExtraKeysLayout,
    sticky: StickyModifierState,
    onKey: (code: Int) -> Unit,
    onText: (text: String) -> Unit,
    onAction: (ExtraKeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(TerminalColors.ExtraKeysBackground)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        for (row in layout.rows) {
            Row(Modifier.fillMaxWidth().height(KEY_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                for (key in row) {
                    val fire: () -> Unit = {
                        when (key) {
                            is ExtraKey.Special -> onKey(key.code)
                            is ExtraKey.Literal -> onText(key.text)
                            is ExtraKey.Toggle -> sticky.tap(key.mod)
                            is ExtraKey.Action -> onAction(key.action)
                        }
                    }
                    val state = (key as? ExtraKey.Toggle)?.let { sticky.state(it.mod) } ?: ModState.Off
                    KeyCell(
                        key = key,
                        state = state,
                        repeatable = (key as? ExtraKey.Special)?.repeatable == true,
                        onPress = fire,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCell(
    key: ExtraKey,
    state: ModState,
    repeatable: Boolean,
    onPress: () -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }

    val background = when {
        state == ModState.Locked -> TerminalColors.Accent
        state == ModState.Once -> TerminalColors.Accent.copy(alpha = 0.28f)
        pressed -> TerminalColors.KeyPressed
        else -> Color.Transparent
    }
    val content = when (state) {
        ModState.Locked -> TerminalColors.OnAccent
        ModState.Once -> TerminalColors.Accent
        ModState.Off -> TerminalColors.KeyLabel
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .focusProperties { canFocus = false }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = key.name
                if (key is ExtraKey.Toggle) {
                    role = Role.Switch
                    toggleableState = if (state != ModState.Off) ToggleableState.On else ToggleableState.Off
                    stateDescription = when (state) {
                        ModState.Off -> "off"
                        ModState.Once -> "on for next key"
                        ModState.Locked -> "locked"
                    }
                }
                onClick { currentOnPress(); true }
            }
            .pointerInput(key, repeatable) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentOnPress()
                    var repeater: Job? = null
                    if (repeatable) {
                        repeater = scope.launch {
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                currentOnPress()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                    }
                    waitForUpOrCancellation()
                    repeater?.cancel()
                    pressed = false
                }
            },
    ) {
        val icon = when (key) {
            is ExtraKey.Special -> key.icon
            is ExtraKey.Action -> key.icon
            else -> null
        }
        when {
            icon == KeyIcon.Menu -> androidx.compose.material3.Icon(
                painterResource(R.drawable.ic_menu),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            icon != null -> ArrowIcon(icon, content)
            else -> Text(
                text = when (key) {
                    is ExtraKey.Special -> key.label
                    is ExtraKey.Literal -> key.label
                    else -> key.name
                },
                color = content,
                fontFamily = KeyFont,
                fontWeight = if (state == ModState.Locked) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** A crisp arrow drawn on a canvas, centred in the key. */
@Composable
private fun ArrowIcon(icon: KeyIcon, color: Color) {
    val rotation = when (icon) {
        KeyIcon.ArrowUp -> 0f
        KeyIcon.ArrowRight -> 90f
        KeyIcon.ArrowDown -> 180f
        else -> 270f
    }
    Canvas(Modifier.size(16.dp).rotate(rotation)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        // Shaft.
        drawLine(color, Offset(w / 2, h * 0.88f), Offset(w / 2, h * 0.14f), stroke, StrokeCap.Round)
        // Head.
        val head = Path().apply {
            moveTo(w * 0.18f, h * 0.46f)
            lineTo(w / 2, h * 0.12f)
            lineTo(w * 0.82f, h * 0.46f)
        }
        drawPath(head, color, style = style)
    }
}

private val KEY_HEIGHT = 44.dp
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L
