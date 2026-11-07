// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.terminal

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * Bridges the soft keyboard (IME) to the terminal.
 *
 * A terminal has no editable text buffer the IME could inspect, so this keeps a
 * small shadow [android.text.Editable] (managed by [BaseInputConnection]) that only
 * ever contains the text currently being composed. Committed text is sent to the
 * terminal immediately and the shadow buffer is cleared again.
 *
 * - `commitText` → text is sent (a `\n` becomes an Enter key press).
 * - `setComposingText` → kept in the shadow buffer and drawn inline at the cursor.
 * - `finishComposingText` → the composed text is committed.
 * - `deleteSurroundingText` with an empty buffer → Backspace / Delete presses.
 * - `sendKeyEvent` → routed through [TerminalView.onKeyDown] by the base class.
 */
internal class TerminalInputConnection(
    private val view: TerminalView,
) : BaseInputConnection(view, true) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // Let the base class drop any composing region first, then send everything.
        super.commitText(text, newCursorPosition)
        flushEditable()
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val handled = super.setComposingText(text, newCursorPosition)
        view.setComposingText(editable?.toString().orEmpty())
        return handled
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val handled = super.setComposingRegion(start, end)
        view.setComposingText(editable?.toString().orEmpty())
        return handled
    }

    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        flushEditable()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val buffer = editable
        if (buffer != null && buffer.isNotEmpty()) {
            // Editing the composition itself; keep the shadow buffer in sync.
            val handled = super.deleteSurroundingText(beforeLength, afterLength)
            view.setComposingText(buffer.toString())
            return handled
        }
        // Nothing composed: the IME means "delete characters in the terminal".
        repeat(beforeLength) { view.sendKey(TerminalKeys.BACKSPACE) }
        repeat(afterLength) { view.sendKey(TerminalKeys.DELETE) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    override fun performEditorAction(actionCode: Int): Boolean {
        // Some keyboards report the Enter key as an editor action instead of a key event.
        flushEditable()
        view.sendKey(TerminalKeys.ENTER)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Commit any pending composition before a raw key so ordering is preserved.
        if (event.action == KeyEvent.ACTION_DOWN) flushEditable()
        return super.sendKeyEvent(event)
    }

    /** Sends the whole shadow buffer to the terminal and clears it. */
    private fun flushEditable() {
        val buffer = editable ?: return
        val text = buffer.toString()
        buffer.clear()
        view.setComposingText("")
        if (text.isNotEmpty()) view.sendText(text)
    }
}
