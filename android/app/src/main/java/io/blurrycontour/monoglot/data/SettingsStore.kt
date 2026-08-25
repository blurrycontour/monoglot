package io.blurrycontour.monoglot.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Transcript visibility. Default is [HIDDEN]: audio is primary, text is a
 *  crutch you reveal on demand. */
enum class TranscriptMode {
    /** No text at all. The default, and the point of the app. */
    HIDDEN,

    /** The sentence playing now, always visible, nothing else. A middle
     *  setting for material that is nearly but not quite parseable. */
    LINE,

    /** Blank until asked, then the current sentence, re-hiding when it ends. */
    REVEAL,

    /** Everything, with the spoken word highlighted. */
    FULL,
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val SPEED = floatPreferencesKey("playback_speed")
        val TRANSCRIPT_MODE = stringPreferencesKey("transcript_mode")
        val THEME = stringPreferencesKey("theme_id")
        val ACCENT = stringPreferencesKey("accent_id")
        val LIBRARY_FILTER = stringPreferencesKey("library_filter")
        val AUTO_UPDATE_CHECK = stringPreferencesKey("auto_update_check")
        val LAST_ITEM = stringPreferencesKey("last_item_id")
        val SERVER_EPOCH = intPreferencesKey("server_epoch")
        val LISTEN_SEEN_AT = stringPreferencesKey("listen_seen_at")
    }

    private val prefs: Flow<Preferences> get() = context.dataStore.data

    /**
     * One preference, emitting only when that preference changes.
     *
     * DataStore emits the whole Preferences object on every write, and `map`
     * passes each one through — so writing any key woke every collector of
     * every other key. That made closing the mini player, which clears the
     * stored last item, re-emit the server epoch: the Listen, Words and
     * System screens all treat that as "the server changed", reset to an
     * empty state and reload. The chips blinked out for a second or two.
     */
    private fun <T> pref(read: (Preferences) -> T): Flow<T> =
        prefs.map(read).distinctUntilChanged()

    val serverUrlFlow: Flow<String> = pref { it[Keys.SERVER_URL] ?: "" }
    val authTokenFlow: Flow<String> = pref { it[Keys.AUTH_TOKEN] ?: "" }
    val speedFlow: Flow<Float> = pref { it[Keys.SPEED] ?: 1.0f }
    val transcriptModeFlow: Flow<TranscriptMode> = pref {
        runCatching { TranscriptMode.valueOf(it[Keys.TRANSCRIPT_MODE] ?: "HIDDEN") }
            .getOrDefault(TranscriptMode.HIDDEN)
    }

    val themeFlow: Flow<String> = pref { it[Keys.THEME] ?: "black" }
    val accentFlow: Flow<String> = pref { it[Keys.ACCENT] ?: "default" }
    val libraryFilterFlow: Flow<String> = pref { it[Keys.LIBRARY_FILTER] ?: "all" }
    val autoUpdateFlow: Flow<Boolean> = pref { (it[Keys.AUTO_UPDATE_CHECK] ?: "true") == "true" }

    suspend fun setTheme(id: String) {
        context.dataStore.edit { it[Keys.THEME] = id }
    }

    suspend fun setAccent(id: String) {
        context.dataStore.edit { it[Keys.ACCENT] = id }
    }

    suspend fun setLibraryFilter(id: String) {
        context.dataStore.edit { it[Keys.LIBRARY_FILTER] = id }
    }

    val lastItemFlow: Flow<Int> = pref { it[Keys.LAST_ITEM]?.toIntOrNull() ?: -1 }

    suspend fun setLastItem(id: Int) {
        context.dataStore.edit { it[Keys.LAST_ITEM] = id.toString() }
    }

    suspend fun setAutoUpdate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = enabled.toString() }
    }

    suspend fun serverUrl(): String = serverUrlFlow.first()
    suspend fun authToken(): String = authTokenFlow.first()
    suspend fun isConfigured(): Boolean = serverUrl().isNotBlank() && authToken().isNotBlank()

    /**
     * Bumped whenever the server actually changes. Everything cached in the
     * app belongs to one server — item ids, progress, downloads, the word list
     * — so screens watch this and start over rather than showing one
     * instance's data under another's address.
     */
    val serverEpochFlow: Flow<Int> = pref { it[Keys.SERVER_EPOCH] ?: 0 }

    /** Returns true if this was a change of server rather than a re-save. */
    suspend fun setServer(url: String, token: String): Boolean {
        val changed = serverUrl() != url.trim() || authToken() != token.trim()
        context.dataStore.edit {
            it[Keys.SERVER_URL] = url.trim()
            it[Keys.AUTH_TOKEN] = token.trim()
            if (changed) it[Keys.SERVER_EPOCH] = (it[Keys.SERVER_EPOCH] ?: 0) + 1
        }
        return changed
    }

    /**
     * When the Listen tab was last opened, for the "new" markers.
     *
     * Read once per launch and immediately replaced: were it re-read every
     * time the tab came forward, swiping to Words and back would clear every
     * marker, and glancing at the list would be enough to lose track of what
     * arrived overnight.
     */
    suspend fun takeListenSeenAt(): Long {
        var previous = 0L
        context.dataStore.edit {
            previous = it[Keys.LISTEN_SEEN_AT]?.toLongOrNull() ?: 0L
            it[Keys.LISTEN_SEEN_AT] = System.currentTimeMillis().toString()
        }
        return previous
    }

    suspend fun setSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.SPEED] = speed }
    }

    suspend fun setTranscriptMode(mode: TranscriptMode) {
        context.dataStore.edit { it[Keys.TRANSCRIPT_MODE] = mode.name }
    }
}
