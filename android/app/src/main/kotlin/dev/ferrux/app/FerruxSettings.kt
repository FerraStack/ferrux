// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
 * that are loaded once, synchronously, when first used (a few small keys), so
 * callers can read them without suspending.
 */
class FerruxSettings private constructor(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _clipboardWrites = MutableStateFlow(ClipboardPolicy.ASK)

    /** What happens when a program writes the clipboard with OSC 52. */
    val clipboardWrites: StateFlow<ClipboardPolicy> = _clipboardWrites.asStateFlow()

    init {
        val initial = runBlocking(Dispatchers.IO) {
            store.data.catch { e ->
                if (e is IOException) {
                    Log.w(FerruxApplication.TAG, "settings unreadable, using defaults", e)
                    emit(emptyPreferences())
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
        _clipboardWrites.value = prefs[CLIPBOARD_WRITES]
            ?.let { name -> ClipboardPolicy.entries.firstOrNull { it.name == name } }
            ?: ClipboardPolicy.ASK
    }

    fun setClipboardWrites(value: ClipboardPolicy) {
        _clipboardWrites.value = value
        scope.launch { store.edit { it[CLIPBOARD_WRITES] = value.name } }
    }

    companion object {
        private val CLIPBOARD_WRITES = stringPreferencesKey("clipboard_writes")

        @Volatile private var instance: FerruxSettings? = null

        fun get(context: Context): FerruxSettings =
            instance ?: synchronized(this) {
                instance ?: FerruxSettings(context).also { instance = it }
            }
    }
}
