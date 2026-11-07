// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferrux.terminal.StickyModifiers
import dev.ferrux.terminal.TerminalKeys

/** CTRL/ALT toggles on the extra-keys row; each applies to the next key or character. */
@Stable
class StickyModifierState : StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    override fun consume(): Int {
        var mods = 0
        if (ctrl) mods = mods or TerminalKeys.MOD_CTRL
        if (alt) mods = mods or TerminalKeys.MOD_ALT
        ctrl = false
        alt = false
        return mods
    }
}

/** One extra key: either a special key, literal text, or a sticky modifier. */
private sealed interface ExtraKey {
    val label: String

    data class Special(override val label: String, val code: Int) : ExtraKey
    data class Literal(override val label: String, val text: String = label) : ExtraKey
    data class Sticky(override val label: String, val mod: Int) : ExtraKey
}

// TODO(M4): make the layout configurable (properties/TOML file), like the research doc's §9.
private val ExtraKeyRows: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Special("ESC", TerminalKeys.ESCAPE),
        ExtraKey.Literal("/"),
        ExtraKey.Literal("-"),
        ExtraKey.Literal("|"),
        ExtraKey.Special("HOME", TerminalKeys.HOME),
        ExtraKey.Special("↑", TerminalKeys.UP),
        ExtraKey.Special("END", TerminalKeys.END),
        ExtraKey.Special("PGUP", TerminalKeys.PAGE_UP),
    ),
    listOf(
        ExtraKey.Special("TAB", TerminalKeys.TAB),
        ExtraKey.Sticky("CTRL", TerminalKeys.MOD_CTRL),
        ExtraKey.Sticky("ALT", TerminalKeys.MOD_ALT),
        ExtraKey.Literal("~"),
        ExtraKey.Special("←", TerminalKeys.LEFT),
        ExtraKey.Special("↓", TerminalKeys.DOWN),
        ExtraKey.Special("→", TerminalKeys.RIGHT),
        ExtraKey.Special("PGDN", TerminalKeys.PAGE_DOWN),
    ),
)

/**
 * Extra keys for soft-keyboard users. Keys are not focusable, so tapping them
 * never steals focus (and the IME connection) from the terminal.
 */
@Composable
fun ExtraKeysRow(
    sticky: StickyModifierState,
    onKey: (code: Int) -> Unit,
    onText: (text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        for (row in ExtraKeyRows) {
            Row(Modifier.fillMaxWidth().height(40.dp)) {
                for (key in row) {
                    val active = key is ExtraKey.Sticky &&
                        (if (key.mod == TerminalKeys.MOD_CTRL) sticky.ctrl else sticky.alt)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .height(40.dp)
                            .focusProperties { canFocus = false }
                            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable(role = Role.Button) {
                                when (key) {
                                    is ExtraKey.Special -> onKey(key.code)
                                    is ExtraKey.Literal -> onText(key.text)
                                    is ExtraKey.Sticky -> when (key.mod) {
                                        TerminalKeys.MOD_CTRL -> { sticky.ctrl = !sticky.ctrl }
                                        else -> { sticky.alt = !sticky.alt }
                                    }
                                }
                            },
                    ) {
                        Text(
                            text = key.label,
                            fontSize = 13.sp,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
