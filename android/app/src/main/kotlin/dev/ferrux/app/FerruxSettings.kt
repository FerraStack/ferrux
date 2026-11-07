// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * User preferences, persisted with DataStore (`files/datastore/settings.preferences_pb`).
 *
 * The few small values are read once, synchronously, when the process starts and are
 * then mirrored in [StateFlow]s, so the first frame already uses them and the pty is
 * not resized twice. Writes update the flow at once and reach disk in the background.
 */
class FerruxSettings internal constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val _fontSizeSp = MutableStateFlow<Float?>(null)

    /** Terminal font size in sp as last set by pinch zoom, or null for the default. */
    val fontSizeSp: StateFlow<Float?> = _fontSizeSp.asStateFlow()

    private val _clipboardWrites = MutableStateFlow(ClipboardPolicy.ASK)

    /** What happens when a program writes the clipboard with OSC 52. */
    val clipboardWrites: StateFlow<ClipboardPolicy> = _clipboardWrites.asStateFlow()

    init {
        apply(runBlocking(Dispatchers.IO) { store.data.orDefaults().first() })
        // Picks up later writes, including ones made through another instance's API.
        scope.launch { store.data.orDefaults().collect(::apply) }
    }

    private fun Flow<Preferences>.orDefaults() = catch { e ->
        if (e !is IOException) throw e
        Log.w(FerruxApplication.TAG, "settings unreadable, using defaults", e)
        emit(emptyPreferences())
    }

    private fun apply(prefs: Preferences) {
        _fontSizeSp.value = prefs[FONT_SIZE_SP]
        _clipboardWrites.value = prefs[CLIPBOARD_WRITES]
            ?.let { name -> ClipboardPolicy.entries.firstOrNull { it.name == name } }
            ?: ClipboardPolicy.ASK
    }

    /** Saves the terminal font size. Returns the write, for tests. */
    fun setFontSize(sp: Float): Job {
        _fontSizeSp.value = sp
        return scope.launch { store.edit { it[FONT_SIZE_SP] = sp } }
    }

    fun setClipboardWrites(value: ClipboardPolicy): Job {
        _clipboardWrites.value = value
        return scope.launch { store.edit { it[CLIPBOARD_WRITES] = value.name } }
    }

    companion object {
        // Keys are part of the on-disk format: never rename one, add a new key instead.
        // TODO(settings screen): offer a font size control as well.
        internal val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        private val CLIPBOARD_WRITES = stringPreferencesKey("clipboard_writes")

        @Volatile private var instance: FerruxSettings? = null

        /** The process-wide instance; the first call reads the file. */
        fun get(context: Context): FerruxSettings =
            instance ?: synchronized(this) {
                instance ?: FerruxSettings(
                    context.applicationContext.settingsStore,
                    CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ).also { instance = it }
            }
    }
}
