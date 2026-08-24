package io.blurrycontour.monoglot.reminders

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.reminderStore by preferencesDataStore(name = "reminders")

class ReminderStore(private val context: Context) {

    private val key = stringPreferencesKey("reminders_json")
    private val json = Json { ignoreUnknownKeys = true }

    val remindersFlow: Flow<List<Reminder>> = context.reminderStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(Reminder.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun all(): List<Reminder> = remindersFlow.first()

    private suspend fun write(list: List<Reminder>) {
        context.reminderStore.edit {
            it[key] = json.encodeToString(ListSerializer(Reminder.serializer()), list)
        }
    }

    suspend fun upsert(reminder: Reminder) {
        val list = all().filterNot { it.id == reminder.id } + reminder
        write(list.sortedWith(compareBy({ it.hour }, { it.minute })))
    }

    suspend fun delete(id: String) {
        write(all().filterNot { it.id == id })
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        all().firstOrNull { it.id == id }?.let { upsert(it.copy(enabled = enabled)) }
    }
}
