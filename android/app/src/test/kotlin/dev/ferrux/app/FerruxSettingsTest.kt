// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FerruxSettingsTest {
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
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun fontSizeDefaultsToNull() {
        withSettings { assertNull(it.fontSizeSp.value) }
    }

    @Test
    fun fontSizeIsVisibleAtOnceAndSurvivesARestart() {
        withSettings { settings ->
            val write = settings.setFontSize(17.5f)
            assertEquals(17.5f, settings.fontSizeSp.value)
            write.join()
        }
        withSettings { assertEquals(17.5f, it.fontSizeSp.value) }
    }

    @Test
    fun corruptFileFallsBackToDefaults() {
        file.writeText("not a protobuf")
        withSettings { settings ->
            assertNull(settings.fontSizeSp.value)
            settings.setFontSize(11f).join()
        }
        withSettings { assertEquals(11f, it.fontSizeSp.value) }
    }
}
