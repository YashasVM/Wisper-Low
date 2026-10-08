package com.wisperlow.mobile.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wisperlow_settings",
    // A corrupt settings file would otherwise throw from the Eagerly-started
    // flow and crash the app on every launch; fall back to defaults instead.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
    /** Show an editable preview before inserting instead of typing immediately. */
    val reviewBeforeInsert: Boolean = false,
    val autoStop: AutoStop = AutoStop.NORMAL,
    /** Keep the speech model in memory between dictations for instant starts. */
    val keepModelLoaded: Boolean = false,
    val historyEnabled: Boolean = true,
    val personalDictionary: Map<String, String> = emptyMap(),
    val onboardingCompleted: Boolean = false,
    val keyboard: KeyboardPrefs = KeyboardPrefs(),
)

/** How the keyboard types; dictation settings live on [WisperlowSettings] itself. */
data class KeyboardPrefs(
    val autoCorrect: Boolean = true,
    val autoCapitalize: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val numberRow: Boolean = false,
    val haptics: Boolean = true,
    val sound: Boolean = false,
    /** Decode speech while it is still being spoken so words appear as they are said. */
    val livePreview: Boolean = true,
    /** Key height relative to the default, from [MIN_HEIGHT_SCALE] to [MAX_HEIGHT_SCALE]. */
    val heightScale: Float = 1f,
) {
    companion object {
        const val MIN_HEIGHT_SCALE = 0.8f
        const val MAX_HEIGHT_SCALE = 1.3f
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val selectedModelId = stringPreferencesKey("selected_model_id")
        val reviewBeforeInsert = booleanPreferencesKey("review_before_insert")
        val autoStop = stringPreferencesKey("auto_stop")
        val keepModelLoaded = booleanPreferencesKey("keep_model_loaded")
        val historyEnabled = booleanPreferencesKey("history_enabled")
        val dictionary = stringPreferencesKey("personal_dictionary")
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val autoCorrect = booleanPreferencesKey("kb_autocorrect")
        val autoCapitalize = booleanPreferencesKey("kb_autocap")
        val doubleSpacePeriod = booleanPreferencesKey("kb_double_space_period")
        val numberRow = booleanPreferencesKey("kb_number_row")
        val haptics = booleanPreferencesKey("kb_haptics")
        val sound = booleanPreferencesKey("kb_sound")
        val livePreview = booleanPreferencesKey("live_preview")
        val heightScale = floatPreferencesKey("kb_height_scale")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<WisperlowSettings> = context.dataStore.data.map { prefs ->
        val defaults = WisperlowSettings()
        WisperlowSettings(
            selectedModelId = prefs[Keys.selectedModelId]
                ?.takeIf { ModelCatalog.byId(it) != null }
                ?: defaults.selectedModelId,
            reviewBeforeInsert = prefs[Keys.reviewBeforeInsert] ?: defaults.reviewBeforeInsert,
            autoStop = parseAutoStop(prefs[Keys.autoStop]),
            keepModelLoaded = prefs[Keys.keepModelLoaded] ?: defaults.keepModelLoaded,
            historyEnabled = prefs[Keys.historyEnabled] ?: defaults.historyEnabled,
            personalDictionary = parseDictionary(prefs[Keys.dictionary] ?: ""),
            onboardingCompleted = prefs[Keys.onboardingCompleted] ?: false,
            keyboard = defaults.keyboard.let { d ->
                KeyboardPrefs(
                    autoCorrect = prefs[Keys.autoCorrect] ?: d.autoCorrect,
                    autoCapitalize = prefs[Keys.autoCapitalize] ?: d.autoCapitalize,
                    doubleSpacePeriod = prefs[Keys.doubleSpacePeriod] ?: d.doubleSpacePeriod,
                    numberRow = prefs[Keys.numberRow] ?: d.numberRow,
                    haptics = prefs[Keys.haptics] ?: d.haptics,
                    sound = prefs[Keys.sound] ?: d.sound,
                    livePreview = prefs[Keys.livePreview] ?: d.livePreview,
                    heightScale = (prefs[Keys.heightScale] ?: d.heightScale)
                        .coerceIn(KeyboardPrefs.MIN_HEIGHT_SCALE, KeyboardPrefs.MAX_HEIGHT_SCALE),
                )
            },
        )
    }

    /** Latest settings for synchronous readers such as the keyboard and audio callbacks. */
    val current: StateFlow<WisperlowSettings> get() = _current.asStateFlow()

    private val _current = MutableStateFlow(WisperlowSettings())
    private val _loaded = MutableStateFlow(false)

    /** False until DataStore delivered its first value; [current] holds defaults until then. */
    val isLoaded: Boolean get() = _loaded.value

    suspend fun awaitLoaded() {
        _loaded.first { it }
    }

    init {
        scope.launch {
            settings.collect { value ->
                _current.value = value
                _loaded.value = true
            }
        }
    }

    suspend fun setSelectedModel(id: String) = edit { it[Keys.selectedModelId] = id }
    suspend fun setReviewBeforeInsert(enabled: Boolean) = edit { it[Keys.reviewBeforeInsert] = enabled }
    suspend fun setAutoStop(value: AutoStop) = edit { it[Keys.autoStop] = value.name }
    suspend fun setKeepModelLoaded(enabled: Boolean) = edit { it[Keys.keepModelLoaded] = enabled }
    suspend fun setHistoryEnabled(enabled: Boolean) = edit { it[Keys.historyEnabled] = enabled }
    suspend fun setOnboardingCompleted(completed: Boolean) = edit { it[Keys.onboardingCompleted] = completed }

    suspend fun setKeyboard(prefs: KeyboardPrefs) = edit {
        it[Keys.autoCorrect] = prefs.autoCorrect
        it[Keys.autoCapitalize] = prefs.autoCapitalize
        it[Keys.doubleSpacePeriod] = prefs.doubleSpacePeriod
        it[Keys.numberRow] = prefs.numberRow
        it[Keys.haptics] = prefs.haptics
        it[Keys.sound] = prefs.sound
        it[Keys.livePreview] = prefs.livePreview
        it[Keys.heightScale] = prefs.heightScale
    }

    suspend fun setPersonalDictionary(dict: Map<String, String>) = edit {
        it[Keys.dictionary] = serializeDictionary(dict)
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
