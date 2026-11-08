// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The foreground-service notification's text, with the real (English) resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationTextTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun sessions(vararg exitCodes: Int?) = exitCodes.mapIndexed { i, code ->
        SessionInfo(id = i + 1L, title = "Session ${i + 1}", exitCode = code)
    }

    @Test
    fun countsSessions() {
        assertEquals("1 session", FerruxService.sessionSummary(res, sessions(null)))
        assertEquals("3 sessions", FerruxService.sessionSummary(res, sessions(null, null, null)))
    }

    @Test
    fun mentionsExitedSessions() {
        assertEquals("2 sessions · 1 exited", FerruxService.sessionSummary(res, sessions(null, 3)))
        assertEquals("1 session · 1 exited", FerruxService.sessionSummary(res, sessions(137)))
    }
}
