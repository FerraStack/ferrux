// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardRequestTest {
    @Test
    fun lengthCountsCharactersNotUtf16Units() {
        assertEquals(8, ClipboardRequest(1, "Session 1", "OSC52-OK").length)
        // 🦀 is two UTF-16 units but one character.
        assertEquals(3, ClipboardRequest(1, "Session 1", "a🦀b").length)
    }

    @Test
    fun askIsTheDefaultPolicyName() {
        // Persisted by name: renaming an entry would silently reset users' choice.
        assertEquals(listOf("OFF", "ASK", "ALLOW"), ClipboardPolicy.entries.map { it.name })
    }
}
