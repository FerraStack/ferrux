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

    /**
     * Recently committed text since the last Enter, so IME deletions counted in
     * UTF-16 units can be turned into the right number of Backspaces (an emoji
     * is 2 units but 1 Backspace).
     */
    private val history = StringBuilder()

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // With CTRL/ALT armed the next character must go out now, not when the
        // IME decides to finish the word.
        if (view.hasActiveModifier()) return commitText(text, newCursorPosition)
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
        repeat(codePointsBeforeCursor(beforeLength)) { view.sendKey(TerminalKeys.BACKSPACE) }
        repeat(afterLength) { view.sendKey(TerminalKeys.DELETE) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val buffer = editable
        if (buffer != null && buffer.isNotEmpty()) {
            val handled = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            view.setComposingText(buffer.toString())
            return handled
        }
        repeat(beforeLength) {
            if (history.isNotEmpty()) {
                history.setLength(history.length - Character.charCount(Character.codePointBefore(history, history.length)))
            }
            view.sendKey(TerminalKeys.BACKSPACE)
        }
        repeat(afterLength) { view.sendKey(TerminalKeys.DELETE) }
        return true
    }

    /** Converts [units] UTF-16 units before the cursor to code points, consuming [history]. */
    private fun codePointsBeforeCursor(units: Int): Int {
        var remaining = units
        var count = 0
        var end = history.length
        while (remaining > 0 && end > 0) {
            val cp = Character.codePointBefore(history, end)
            val n = Character.charCount(cp)
            end -= n
            remaining -= n
            count++
        }
        history.setLength(end)
        // Text we never saw (typed before this connection): one unit each.
        return count + maxOf(0, remaining)
    }

    private fun remember(text: String) {
        val nl = text.lastIndexOf('\n')
        if (nl >= 0) {
            history.setLength(0)
            history.append(text, nl + 1, text.length)
        } else {
            history.append(text)
        }
        if (history.length > HISTORY_LIMIT) history.delete(0, history.length - HISTORY_LIMIT)
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        // Some keyboards report the Enter key as an editor action instead of a key event.
        flushEditable()
        history.setLength(0)
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
        if (text.isNotEmpty()) {
            remember(text)
            view.sendText(text)
        }
        // Our shadow buffer is empty again; tell the IME so it doesn't try to
        // re-compose (and re-send) the word it just committed.
        view.resetImeSelection()
    }

    private companion object {
        const val HISTORY_LIMIT = 256
    }
}
