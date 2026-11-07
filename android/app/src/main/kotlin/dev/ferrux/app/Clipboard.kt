// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

/** What happens when a program writes the clipboard with OSC 52. */
enum class ClipboardPolicy {
    /** Ignore the request. */
    OFF,

    /** Ask each time, with a snackbar (the default). */
    ASK,

    /** Copy without asking. */
    ALLOW,
}

/** An OSC 52 clipboard write from [sessionTitle] waiting for the user's answer. */
data class ClipboardRequest(val sessionId: Long, val sessionTitle: String, val text: String) {
    /** Characters as the user counts them (code points, not UTF-16 units). */
    val length: Int get() = text.codePointCount(0, text.length)
}
