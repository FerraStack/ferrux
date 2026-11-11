// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.view.KeyEvent

/** What a Ctrl+Alt session shortcut on a hardware keyboard does. */
sealed interface SessionShortcut {
    /** Ctrl+Alt+C: a new session. */
    data object New : SessionShortcut

    /** Ctrl+Alt+↓ / N: the next session (+1); Ctrl+Alt+↑ / P: the previous one (-1). */
    data class Move(val delta: Int) : SessionShortcut

    /** Ctrl+Alt+1 … 9: the session at that position (0-based [index]). */
    data class Go(val index: Int) : SessionShortcut
}

/**
 * Hardware-keyboard session shortcuts, following Termux: Ctrl+Alt+C for a new
 * session, Ctrl+Alt+↑/↓ (or P/N) to switch, Ctrl+Alt+1…9 to jump to a session.
 */
object SessionShortcuts {
    /** The shortcut for [keyCode] pressed with Ctrl+Alt, or null if it isn't one. */
    fun of(keyCode: Int): SessionShortcut? = when (keyCode) {
        KeyEvent.KEYCODE_C -> SessionShortcut.New
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_N -> SessionShortcut.Move(+1)
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_P -> SessionShortcut.Move(-1)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> SessionShortcut.Go(keyCode - KeyEvent.KEYCODE_1)
        else -> null
    }

    /**
     * The id [delta] places after [current] in [ids], wrapping around at either end;
     * null if there is nowhere else to go.
     */
    fun neighbour(ids: List<Long>, current: Long, delta: Int): Long? {
        if (ids.size < 2) return null
        val at = ids.indexOf(current)
        if (at < 0) return ids.first()
        return ids[Math.floorMod(at + delta, ids.size)]
    }
}
