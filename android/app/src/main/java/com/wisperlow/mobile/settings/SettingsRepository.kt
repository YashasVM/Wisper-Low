package com.wisperlow.mobile.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wisperlow.mobile.stt.ModelCatalog
import com.wisperlow.mobile.text.PolishMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wisperlow_settings")

enum class InsertionPreference {
    QUICK_INSERT,
    REVIEW_FIRST,
}

data class VocabularyHint(
    val canonical: String,
    val context: String = "",
)

data class WisperlowSettings(
    val selectedModelId: String = ModelCatalog.PARAKEET_V3_INT8.id,
    val bubbleEnabled: Boolean = true,
    val personalDictionary: Map<String, String> = emptyMap(),
    val polishMode: PolishMode = PolishMode.ORIGINAL,
    val insertionPreference: InsertionPreference = InsertionPreference.REVIEW_FIRST,
    val historyEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val practiceCompleted: Boolean = false,
    /** Contextual canonical terms supplied to polishing, separate from exact aliases. */
    val vocabularyHints: List<VocabularyHint> = emptyList(),
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val selectedModelId = stringPreferencesKey("selected_model_id")
        val bubbleEnabled = androidx.datastore.preferences.core.booleanPreferencesKey("bubble_enabled")
        val dictionary = stringPreferencesKey("personal_dictionary")
        val polishMode = stringPreferencesKey("polish_mode")
        val insertionPreference = stringPreferencesKey("insertion_preference")
        val historyEnabled = androidx.datastore.preferences.core.booleanPreferencesKey("history_enabled")
        val onboardingCompleted = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_completed")
        val practiceCompleted = androidx.datastore.preferences.core.booleanPreferencesKey("practice_completed")
        val vocabularyHints = stringPreferencesKey("vocabulary_hints")
    }

    val settings: Flow<WisperlowSettings> = context.dataStore.data.map { prefs ->
        val selectedModelId = prefs[Keys.selectedModelId]
            ?.takeIf { ModelCatalog.byId(it) != null }
            ?: ModelCatalog.PARAKEET_V3_INT8.id
        WisperlowSettings(
            selectedModelId = selectedModelId,
            bubbleEnabled = prefs[Keys.bubbleEnabled] ?: true,
            personalDictionary = parseDictionary(prefs[Keys.dictionary] ?: ""),
            polishMode = parsePolishMode(prefs[Keys.polishMode]),
            insertionPreference = parseInsertionPreference(prefs[Keys.insertionPreference]),
            historyEnabled = prefs[Keys.historyEnabled] ?: true,
            onboardingCompleted = prefs[Keys.onboardingCompleted] ?: false,
            practiceCompleted = prefs[Keys.practiceCompleted] ?: false,
            vocabularyHints = parseVocabularyHints(prefs[Keys.vocabularyHints] ?: ""),
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

    suspend fun setPolishMode(mode: PolishMode) {
        context.dataStore.edit { it[Keys.polishMode] = mode.name }
    }

    suspend fun setInsertionPreference(preference: InsertionPreference) {
        context.dataStore.edit { it[Keys.insertionPreference] = preference.name }
    }

    suspend fun setHistoryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.historyEnabled] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    suspend fun setPracticeCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.practiceCompleted] = completed }
    }

    suspend fun setVocabularyHints(hints: List<VocabularyHint>) {
        context.dataStore.edit { it[Keys.vocabularyHints] = serializeVocabularyHints(hints) }
    }

    companion object {
        fun parsePolishMode(raw: String?): PolishMode = parseEnum(raw, PolishMode.ORIGINAL)

        fun parseInsertionPreference(raw: String?): InsertionPreference =
            parseEnum(raw, InsertionPreference.REVIEW_FIRST)

        fun parseDictionary(raw: String): Map<String, String> =
            raw.lineSequence()
                .filter { it.contains('=') }
                .map { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                }
                .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
                .toMap()

        fun parseVocabularyHints(raw: String): List<VocabularyHint> =
            raw.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 2) return@mapNotNull null
                    runCatching {
                        VocabularyHint(
                            canonical = decode(parts[0]),
                            context = decode(parts[1]),
                        )
                    }.getOrNull()
                }
                .filter { it.canonical.isNotBlank() }
                .distinct()
                .toList()

        fun serializeVocabularyHints(hints: List<VocabularyHint>): String =
            hints.asSequence()
                .map { hint ->
                    val canonical = hint.canonical.replace('\n', ' ').trim()
                    val context = hint.context.replace('\n', ' ').trim()
                    canonical to context
                }
                .filter { (canonical, _) -> canonical.isNotEmpty() }
                .distinct()
                .joinToString("\n") { (canonical, context) ->
                    "${encode(canonical)}\t${encode(context)}"
                }

        private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T =
            raw?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { value -> runCatching { enumValueOf<T>(value.uppercase()) }.getOrNull() }
                ?: fallback

        private fun encode(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decode(value: String): String =
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }
}
