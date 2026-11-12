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

/** Cursor shape until a program picks one (DECSCUSR). [code] is the SnapshotLayout `CURSOR_*` code. */
enum class CursorStyle(val code: Int) {
    BLOCK(1),
    UNDERLINE(2),
    BAR(3),
}

/** What the terminal bell (BEL, `\a`) does. */
enum class BellMode {
    VIBRATE,
    BEEP,
    SILENT,
}

/** The extra-keys row above the keyboard. A user-defined JSON layout comes later. */
enum class ExtraKeysPreset {
    /** Two rows, as in Termux. */
    TWO_ROWS,

    /** One row with the essentials. */
    ONE_ROW,

    /** No extra keys; the drawer still opens with a swipe from the left edge. */
    HIDDEN,
}

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

    /** Terminal font size in sp (from Settings or pinch zoom), or null for the default. */
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

    private val _theme = MutableStateFlow(DEFAULT_THEME)

    /** Id of the colour theme (`crates/ferrux-term/src/color.rs`). Unknown ids fall back to the default. */
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _cursorStyle = MutableStateFlow(CursorStyle.BLOCK)
    val cursorStyle: StateFlow<CursorStyle> = _cursorStyle.asStateFlow()

    private val _cursorBlink = MutableStateFlow(false)
    val cursorBlink: StateFlow<Boolean> = _cursorBlink.asStateFlow()

    private val _extraKeys = MutableStateFlow(ExtraKeysPreset.TWO_ROWS)
    val extraKeys: StateFlow<ExtraKeysPreset> = _extraKeys.asStateFlow()

    private val _bell = MutableStateFlow(BellMode.VIBRATE)
    val bell: StateFlow<BellMode> = _bell.asStateFlow()

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
        _theme.value = prefs[THEME] ?: DEFAULT_THEME
        _cursorStyle.value = enumOf(prefs[CURSOR_STYLE], CursorStyle.BLOCK)
        _cursorBlink.value = prefs[CURSOR_BLINK] ?: false
        _extraKeys.value = enumOf(prefs[EXTRA_KEYS], ExtraKeysPreset.TWO_ROWS)
        _bell.value = enumOf(prefs[BELL], BellMode.VIBRATE)
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

    fun setTheme(id: String): Job {
        _theme.value = id
        return scope.launch { store.edit { it[THEME] = id } }
    }

    fun setCursorStyle(value: CursorStyle): Job {
        _cursorStyle.value = value
        return scope.launch { store.edit { it[CURSOR_STYLE] = value.name } }
    }

    fun setCursorBlink(blink: Boolean): Job {
        _cursorBlink.value = blink
        return scope.launch { store.edit { it[CURSOR_BLINK] = blink } }
    }

    fun setExtraKeys(value: ExtraKeysPreset): Job {
        _extraKeys.value = value
        return scope.launch { store.edit { it[EXTRA_KEYS] = value.name } }
    }

    fun setBell(value: BellMode): Job {
        _bell.value = value
        return scope.launch { store.edit { it[BELL] = value.name } }
    }

    /** `key:value` pairs for the CI debug dump. */
    fun debugState(): String = listOf(
        "theme" to theme.value,
        "fontSize" to (fontSizeSp.value?.toString() ?: "default"),
        "cursor" to cursorStyle.value.name,
        "blink" to cursorBlink.value.toString(),
        "extraKeys" to extraKeys.value.name,
        "bell" to bell.value.name,
        "closeOnCleanExit" to closeOnCleanExit.value.toString(),
        "keepScreenOn" to keepScreenOn.value.toString(),
        "clipboard" to clipboardWrites.value.name,
    ).joinToString(",") { (k, v) -> "$k:$v" }

    companion object {
        const val DEFAULT_THEME = "ferrux-night"
        const val DEFAULT_FONT_SIZE_SP = 13f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 24f

        // Keys are part of the on-disk format: never rename one, add a new key instead.
        // All of them are offered on the Settings screen (ui/SettingsScreen.kt).
        internal val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        internal val CLIPBOARD_WRITES = stringPreferencesKey("clipboard_writes")
        internal val CLOSE_ON_CLEAN_EXIT = booleanPreferencesKey("close_on_clean_exit")
        internal val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        internal val THEME = stringPreferencesKey("theme")
        internal val CURSOR_STYLE = stringPreferencesKey("cursor_style")
        internal val CURSOR_BLINK = booleanPreferencesKey("cursor_blink")
        internal val EXTRA_KEYS = stringPreferencesKey("extra_keys")
        internal val BELL = stringPreferencesKey("bell")

        private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

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
