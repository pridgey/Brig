package dev.pridgey.brig.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "brig")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings_json")

    val flow: StateFlow<BrigSettings> = context.dataStore.data
        .map { decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, BrigSettings())

    /** Reads straight from disk; use when the settings must be loaded (e.g. at boot). */
    suspend fun load(): BrigSettings = decode(context.dataStore.data.first()[key])

    suspend fun update(transform: (BrigSettings) -> BrigSettings) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(BrigSettings.serializer(), transform(decode(prefs[key])))
        }
    }

    fun exportJson(): String = prettyJson.encodeToString(BrigSettings.serializer(), flow.value)

    suspend fun importJson(text: String) {
        val parsed = json.decodeFromString(BrigSettings.serializer(), text)
        update { parsed }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
        private val prettyJson = Json(json) { prettyPrint = true }

        fun decode(raw: String?): BrigSettings =
            raw?.let { runCatching { json.decodeFromString(BrigSettings.serializer(), it) }.getOrNull() }
                ?: BrigSettings()
    }
}
