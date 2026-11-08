// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.api

/**
 * How `IFerruxApi.ERR_*` errors cross Binder (docs/API.md §7).
 *
 * Binder only carries a fixed set of exception types, and `ServiceSpecificException`
 * is not part of the app SDK. Ferrux therefore throws an [IllegalStateException]
 * whose message is `E<code>: <text>`, e.g. `E4: /etc/hosts: outside the home
 * directory`; [parse] reads it back. Used by the service and by `ferrux-client`.
 */
object ApiErrors {
    private val FORMAT = Regex("""^E(\d+): (.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** The exception for error [code] (one of `IFerruxApi.ERR_*`). */
    @JvmStatic
    fun exception(code: Int, message: String): IllegalStateException = IllegalStateException("E$code: $message")

    /** `(code, text)` if [t] is an API error from [exception], else null. */
    @JvmStatic
    fun parse(t: Throwable): Pair<Int, String>? {
        if (t !is IllegalStateException) return null
        val m = FORMAT.matchEntire(t.message ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2]
    }
}
