// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * User preferences, persisted with DataStore. Values are mirrored in [StateFlow]s
 * that are loaded once, synchronously, at process start (a few small keys), so the
 * first frame already uses them and the terminal is never resized twice.
 */
class FerruxSettings private constructor(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _fontSizeSp = MutableStateFlow<Float?>(null)

    /** Terminal font size in sp, or null for the default. */
    val fontSizeSp: StateFlow<Float?> = _fontSizeSp.asStateFlow()

    private val _keepExited = MutableStateFlow(KeepExited.ON_ERROR)

    /** Whether a session whose process ended stays open with a restart prompt. */
    val keepExited: StateFlow<KeepExited> = _keepExited.asStateFlow()

    init {
        val initial = runBlocking(Dispatchers.IO) {
            store.data.catch { e ->
                if (e is IOException) {
                    Log.w(FerruxApplication.TAG, "settings unreadable, using defaults", e)
                    emit(androidx.datastore.preferences.core.emptyPreferences())
                } else {
                    throw e
                }
            }.first()
        }
        apply(initial)
        scope.launch {
            store.data.catch { /* keep the last good values */ }.collect(::apply)
        }
    }

    private fun apply(prefs: Preferences) {
        _fontSizeSp.value = prefs[FONT_SIZE_SP]
        _keepExited.value = prefs[KEEP_EXITED]
            ?.let { name -> KeepExited.entries.firstOrNull { it.name == name } }
            ?: KeepExited.ON_ERROR
    }

    fun setKeepExited(value: KeepExited) {
        _keepExited.value = value
        scope.launch { store.edit { it[KEEP_EXITED] = value.name } }
    }

    fun setFontSize(sp: Float) {
        _fontSizeSp.value = sp
        scope.launch { store.edit { it[FONT_SIZE_SP] = sp } }
    }

    companion object {
        private val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        private val KEEP_EXITED = stringPreferencesKey("keep_exited")

        @Volatile private var instance: FerruxSettings? = null

        fun get(context: Context): FerruxSettings =
            instance ?: synchronized(this) {
                instance ?: FerruxSettings(context).also { instance = it }
            }
    }
}
