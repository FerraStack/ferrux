// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import kotlinx.coroutines.flow.drop
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

    private val _closeOnCleanExit = MutableStateFlow(true)

    /**
     * Whether a session whose shell exits with status 0 closes at once. Otherwise
     * it stays open, like a failed one, with a "press Enter to restart" notice.
     */
    val closeOnCleanExit: StateFlow<Boolean> = _closeOnCleanExit.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(false)

    /** Whether the screen stays on while the terminal is shown. Off by default. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    init {
        apply(runBlocking(Dispatchers.IO) { store.data.orDefaults().first() })
        // Picks up later changes to the file. The first emission is the state just
        // applied above; skipping it keeps it from overwriting a value set (and shown)
        // since, before that value's write has landed.
        scope.launch { store.data.orDefaults().drop(1).collect(::apply) }
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
        _closeOnCleanExit.value = prefs[CLOSE_ON_CLEAN_EXIT] ?: true
        _keepScreenOn.value = prefs[KEEP_SCREEN_ON] ?: false
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

    /** See [closeOnCleanExit]. Returns the write, for tests. */
    fun setCloseOnCleanExit(close: Boolean): Job {
        _closeOnCleanExit.value = close
        return scope.launch { store.edit { it[CLOSE_ON_CLEAN_EXIT] = close } }
    }

    /** See [keepScreenOn]. Returns the write, for tests. */
    fun setKeepScreenOn(on: Boolean): Job {
        _keepScreenOn.value = on
        return scope.launch { store.edit { it[KEEP_SCREEN_ON] = on } }
    }

    companion object {
        // Keys are part of the on-disk format: never rename one, add a new key instead.
        // TODO(settings screen): offer a font size control as well.
        internal val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        private val CLIPBOARD_WRITES = stringPreferencesKey("clipboard_writes")

        // TODO(settings screen): a "Close sessions that exit cleanly" switch.
        internal val CLOSE_ON_CLEAN_EXIT = booleanPreferencesKey("close_on_clean_exit")

        // TODO(settings screen): a "Keep screen on" switch.
        internal val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

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
