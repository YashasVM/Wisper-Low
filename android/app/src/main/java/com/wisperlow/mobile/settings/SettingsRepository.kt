package com.wisperlow.mobile.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wisperlow.mobile.stt.ModelCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wisperlow_settings")

/** How long a pause in speech must last before dictation finishes on its own. */
enum class AutoStop(val silenceMs: Long) {
    SHORT(1_000L),
    NORMAL(1_600L),
    RELAXED(2_600L),
    LONG(4_000L),

    /** Only an explicit tap finishes dictation. */
    MANUAL(0L),
}

data class WisperlowSettings(
    val selectedModelId: String = ModelCatalog.DEFAULT.id,
    val bubbleEnabled: Boolean = true,
    /** Show the bubble only while a keyboard is open in another app. Needs Accessibility. */
    val bubbleOnlyWhenTyping: Boolean = true,
    /** Show an editable preview before inserting instead of typing immediately. */
    val reviewBeforeInsert: Boolean = false,
    val autoStop: AutoStop = AutoStop.NORMAL,
    /** Keep the speech model in memory between dictations for instant starts. */
    val keepModelLoaded: Boolean = false,
    val historyEnabled: Boolean = true,
    val personalDictionary: Map<String, String> = emptyMap(),
    val onboardingCompleted: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val selectedModelId = stringPreferencesKey("selected_model_id")
        val bubbleEnabled = booleanPreferencesKey("bubble_enabled")
        val bubbleOnlyWhenTyping = booleanPreferencesKey("bubble_only_when_typing")
        val reviewBeforeInsert = booleanPreferencesKey("review_before_insert")
        val autoStop = stringPreferencesKey("auto_stop")
        val keepModelLoaded = booleanPreferencesKey("keep_model_loaded")
        val historyEnabled = booleanPreferencesKey("history_enabled")
        val dictionary = stringPreferencesKey("personal_dictionary")
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val bubbleX = intPreferencesKey("bubble_x")
        val bubbleY = intPreferencesKey("bubble_y")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<WisperlowSettings> = context.dataStore.data.map { prefs ->
        val defaults = WisperlowSettings()
        WisperlowSettings(
            selectedModelId = prefs[Keys.selectedModelId]
                ?.takeIf { ModelCatalog.byId(it) != null }
                ?: defaults.selectedModelId,
            bubbleEnabled = prefs[Keys.bubbleEnabled] ?: defaults.bubbleEnabled,
            bubbleOnlyWhenTyping = prefs[Keys.bubbleOnlyWhenTyping] ?: defaults.bubbleOnlyWhenTyping,
            reviewBeforeInsert = prefs[Keys.reviewBeforeInsert] ?: defaults.reviewBeforeInsert,
            autoStop = parseAutoStop(prefs[Keys.autoStop]),
            keepModelLoaded = prefs[Keys.keepModelLoaded] ?: defaults.keepModelLoaded,
            historyEnabled = prefs[Keys.historyEnabled] ?: defaults.historyEnabled,
            personalDictionary = parseDictionary(prefs[Keys.dictionary] ?: ""),
            onboardingCompleted = prefs[Keys.onboardingCompleted] ?: false,
        )
    }

    /** Latest settings for synchronous readers such as the overlay and audio callbacks. */
    val current: StateFlow<WisperlowSettings> =
        settings.stateIn(scope, SharingStarted.Eagerly, WisperlowSettings())

    val bubblePosition: Flow<Pair<Int, Int>?> = context.dataStore.data.map { prefs ->
        val x = prefs[Keys.bubbleX] ?: return@map null
        val y = prefs[Keys.bubbleY] ?: return@map null
        x to y
    }

    suspend fun setSelectedModel(id: String) = edit { it[Keys.selectedModelId] = id }
    suspend fun setBubbleEnabled(enabled: Boolean) = edit { it[Keys.bubbleEnabled] = enabled }
    suspend fun setBubbleOnlyWhenTyping(enabled: Boolean) = edit { it[Keys.bubbleOnlyWhenTyping] = enabled }
    suspend fun setReviewBeforeInsert(enabled: Boolean) = edit { it[Keys.reviewBeforeInsert] = enabled }
    suspend fun setAutoStop(value: AutoStop) = edit { it[Keys.autoStop] = value.name }
    suspend fun setKeepModelLoaded(enabled: Boolean) = edit { it[Keys.keepModelLoaded] = enabled }
    suspend fun setHistoryEnabled(enabled: Boolean) = edit { it[Keys.historyEnabled] = enabled }
    suspend fun setOnboardingCompleted(completed: Boolean) = edit { it[Keys.onboardingCompleted] = completed }

    suspend fun setPersonalDictionary(dict: Map<String, String>) = edit {
        it[Keys.dictionary] = serializeDictionary(dict)
    }

    suspend fun setBubblePosition(x: Int, y: Int) = edit {
        it[Keys.bubbleX] = x
        it[Keys.bubbleY] = y
    }

    suspend fun clearBubblePosition() = edit {
        it.remove(Keys.bubbleX)
        it.remove(Keys.bubbleY)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        fun parseAutoStop(raw: String?): AutoStop =
            raw?.trim()?.uppercase()?.let { value -> AutoStop.entries.firstOrNull { it.name == value } }
                ?: AutoStop.NORMAL

        fun parseDictionary(raw: String): Map<String, String> =
            raw.lineSequence()
                .filter { it.contains('=') }
                .map { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                }
                .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
                .toMap()

        fun serializeDictionary(dict: Map<String, String>): String =
            dict.entries.joinToString("\n") { (k, v) ->
                "${k.replace("\n", " ").replace("=", " ").trim()}=${v.replace("\n", " ").trim()}"
            }
    }
}
