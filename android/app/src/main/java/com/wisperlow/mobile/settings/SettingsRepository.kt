package com.wisperlow.mobile.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wisperlow.mobile.stt.ModelCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wisperlow_settings")

data class WisperlowSettings(
    val selectedModelId: String = ModelCatalog.PARAKEET_V3_INT8.id,
    val bubbleEnabled: Boolean = true,
    val personalDictionary: Map<String, String> = emptyMap(),
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val selectedModelId = stringPreferencesKey("selected_model_id")
        val bubbleEnabled = androidx.datastore.preferences.core.booleanPreferencesKey("bubble_enabled")
        val dictionary = stringPreferencesKey("personal_dictionary")
    }

    val settings: Flow<WisperlowSettings> = context.dataStore.data.map { prefs ->
        val selectedModelId = prefs[Keys.selectedModelId]
            ?.takeIf { ModelCatalog.byId(it) != null }
            ?: ModelCatalog.PARAKEET_V3_INT8.id
        WisperlowSettings(
            selectedModelId = selectedModelId,
            bubbleEnabled = prefs[Keys.bubbleEnabled] ?: true,
            personalDictionary = parseDictionary(prefs[Keys.dictionary] ?: ""),
        )
    }

    suspend fun setSelectedModel(id: String) {
        context.dataStore.edit { it[Keys.selectedModelId] = id }
    }

    suspend fun setBubbleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.bubbleEnabled] = enabled }
    }

    suspend fun setPersonalDictionary(dict: Map<String, String>) {
        context.dataStore.edit {
            it[Keys.dictionary] = dict.entries.joinToString("\n") { (k, v) ->
                "${k.trim()}=${v.replace("\n", " ")}"
            }
        }
    }

    companion object {
        fun parseDictionary(raw: String): Map<String, String> =
            raw.lineSequence()
                .filter { it.contains('=') }
                .map { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                }
                .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
                .toMap()
    }
}
