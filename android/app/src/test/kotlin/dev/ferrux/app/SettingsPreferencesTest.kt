// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Settings screen's preferences survive a restart and tolerate bad values. */
class SettingsPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file by lazy { File(tmp.root, "settings.preferences_pb") }

    /** Opens the settings file like a fresh process would, runs [block], then closes it. */
    private fun <T> withSettings(block: suspend (FerruxSettings) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { file }
        try {
            block(FerruxSettings(store, scope))
        } finally {
            // DataStore allows one open instance per file; release it for the next "process".
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun defaults() = withSettings { s ->
        assertEquals(FerruxSettings.DEFAULT_THEME, s.theme.value)
        assertNull(s.fontSizeSp.value)
        assertEquals(CursorStyle.BLOCK, s.cursorStyle.value)
        assertFalse(s.cursorBlink.value)
        assertEquals(ExtraKeysPreset.TWO_ROWS, s.extraKeys.value)
        assertEquals(BellMode.VIBRATE, s.bell.value)
        assertFalse(s.keepScreenOn.value)
        assertEquals(ClipboardPolicy.ASK, s.clipboardWrites.value)
    }

    @Test
    fun valuesSurviveARestart() {
        withSettings { s ->
            listOf(
                s.setTheme("nord"),
                s.setFontSize(17f),
                s.setCursorStyle(CursorStyle.BAR),
                s.setCursorBlink(true),
                s.setExtraKeys(ExtraKeysPreset.ONE_ROW),
                s.setBell(BellMode.SILENT),
                s.setKeepScreenOn(true),
                s.setClipboardWrites(ClipboardPolicy.OFF),
            ).forEach { it.join() }
        }
        withSettings { s ->
            assertEquals("nord", s.theme.value)
            assertEquals(17f, s.fontSizeSp.value)
            assertEquals(CursorStyle.BAR, s.cursorStyle.value)
            assertTrue(s.cursorBlink.value)
            assertEquals(ExtraKeysPreset.ONE_ROW, s.extraKeys.value)
            assertEquals(BellMode.SILENT, s.bell.value)
            assertTrue(s.keepScreenOn.value)
            assertEquals(ClipboardPolicy.OFF, s.clipboardWrites.value)
            assertEquals(
                "theme:nord,fontSize:17.0,cursor:BAR,blink:true,extraKeys:ONE_ROW,bell:SILENT," +
                    "closeOnCleanExit:true,keepScreenOn:true,clipboard:OFF",
                s.debugState(),
            )
        }
    }

    @Test
    fun unknownEnumNamesFallBackToDefaults() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            store.edit {
                it[FerruxSettings.CURSOR_STYLE] = "TRIANGLE"
                it[FerruxSettings.BELL] = "TRUMPET"
            }
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
        withSettings { s ->
            assertEquals(CursorStyle.BLOCK, s.cursorStyle.value)
            assertEquals(BellMode.VIBRATE, s.bell.value)
        }
    }
}
