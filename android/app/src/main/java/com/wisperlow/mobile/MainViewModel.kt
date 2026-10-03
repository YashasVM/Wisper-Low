package com.wisperlow.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisperlow.mobile.accessibility.WisperlowAccessibilityService
import com.wisperlow.mobile.dictation.DictationEngine
import com.wisperlow.mobile.dictation.DictationError
import com.wisperlow.mobile.dictation.DictationState
import com.wisperlow.mobile.history.TranscriptEntry
import com.wisperlow.mobile.history.TranscriptRepository
import com.wisperlow.mobile.service.DictationService
import com.wisperlow.mobile.settings.AutoStop
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.settings.WisperlowSettings
import com.wisperlow.mobile.stt.DownloadState
import com.wisperlow.mobile.stt.ModelCatalog
import com.wisperlow.mobile.stt.ModelDownloader
import com.wisperlow.mobile.stt.SttModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Permission and access state read from Android when the app resumes. */
data class SystemAccess(
    val microphone: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val accessibilityEnabled: Boolean = false,
)

data class SetupStatus(
    val microphone: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val accessibility: Boolean,
    val model: Boolean,
) {
    /** Accessibility only improves insertion; the bubble works without it by copying. */
    val canRunBubble: Boolean get() = microphone && overlay && model
    val allDone: Boolean get() = canRunBubble && accessibility
}

data class PracticeUi(
    val text: String = "",
    val error: DictationError? = null,
)

data class MainUiState(
    val settings: WisperlowSettings = WisperlowSettings(),
    val setup: SetupStatus = SetupStatus(false, false, false, false, false),
    val downloads: Map<String, DownloadState> = emptyMap(),
    val history: List<TranscriptEntry> = emptyList(),
    val bubbleRunning: Boolean = false,
    val dictation: DictationState = DictationState.Idle,
    val practice: PracticeUi = PracticeUi(),
    val settingsLoaded: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val modelDownloader: ModelDownloader,
    private val engine: DictationEngine,
) : ViewModel() {

    private val access = MutableStateFlow(SystemAccess())
    private val practice = MutableStateFlow(PracticeUi())
    private var practiceOwned = false

    private val setup = combine(
        access,
        WisperlowAccessibilityService.connected,
        modelDownloader.states,
    ) { access, connected, downloads ->
        SetupStatus(
            microphone = access.microphone,
            notifications = access.notifications,
            overlay = access.overlay,
            accessibility = access.accessibilityEnabled || connected,
            model = downloads.values.any { it is DownloadState.Completed },
        )
    }

    private val loadedSettings = MutableStateFlow<WisperlowSettings?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        combine(loadedSettings, setup, modelDownloader.states, ::Triple),
        transcriptRepository.entries,
        DictationService.running,
        engine.state,
        practice,
    ) { (settings, setup, downloads), history, running, dictation, practice ->
        MainUiState(
            settings = settings ?: WisperlowSettings(),
            setup = setup,
            downloads = downloads,
            history = history,
            bubbleRunning = running,
            dictation = dictation,
            practice = practice,
            settingsLoaded = settings != null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        viewModelScope.launch { settingsRepository.settings.collect { loadedSettings.value = it } }
        viewModelScope.launch { transcriptRepository.load() }
        modelDownloader.refresh()
    }

    /** Microphone loudness while dictating, for the try-it button. */
    val level: StateFlow<Float> = engine.level

    fun updateAccess(value: SystemAccess) {
        access.value = value
    }

    // ---- models ----

    fun downloadModel(model: SttModel, allowMobileData: Boolean) {
        viewModelScope.launch {
            // Pick the model the user is downloading if nothing usable is installed yet.
            val current = settingsRepository.current.value.selectedModelId
            if (modelDownloader.installedDirFor(current) == null) settingsRepository.setSelectedModel(model.id)
            runCatching { modelDownloader.download(model, allowMobileData) }
        }
    }

    fun cancelDownload(model: SttModel) = modelDownloader.cancelDownload(model.id)

    fun selectModel(model: SttModel) {
        viewModelScope.launch {
            settingsRepository.setSelectedModel(model.id)
            // The next dictation loads the new model; free the old one now.
            engine.releaseModelIfIdle()
        }
    }

    fun deleteModel(model: SttModel) {
        viewModelScope.launch {
            engine.releaseModel()
            modelDownloader.deleteModel(model.id)
            if (settingsRepository.current.value.selectedModelId == model.id) {
                val fallback = ModelCatalog.all.firstOrNull { modelDownloader.installedDirFor(it.id) != null }
                settingsRepository.setSelectedModel((fallback ?: ModelCatalog.DEFAULT).id)
            }
        }
    }

    // ---- settings ----

    fun setBubbleEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepository.setBubbleEnabled(enabled) }
    fun setBubbleOnlyWhenTyping(enabled: Boolean) = viewModelScope.launch { settingsRepository.setBubbleOnlyWhenTyping(enabled) }
    fun setReviewBeforeInsert(enabled: Boolean) = viewModelScope.launch { settingsRepository.setReviewBeforeInsert(enabled) }
    fun setAutoStop(value: AutoStop) = viewModelScope.launch { settingsRepository.setAutoStop(value) }
    fun setHistoryEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepository.setHistoryEnabled(enabled) }
    fun resetBubblePosition() = viewModelScope.launch { settingsRepository.clearBubblePosition() }

    fun setKeepModelLoaded(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setKeepModelLoaded(enabled)
        if (enabled) engine.preload() else engine.releaseModelIfIdle()
    }

    fun completeOnboarding() = viewModelScope.launch {
        settingsRepository.setOnboardingCompleted(true)
        settingsRepository.setBubbleEnabled(true)
    }

    // ---- words ----

    fun saveWord(originalSpoken: String?, spoken: String, written: String) {
        val key = spoken.trim().lowercase()
        val value = written.trim()
        if (key.isEmpty() || value.isEmpty()) return
        viewModelScope.launch {
            val updated = settingsRepository.current.value.personalDictionary.toMutableMap()
            originalSpoken?.let { updated.remove(it) }
            updated[key] = value
            settingsRepository.setPersonalDictionary(updated)
        }
    }

    fun deleteWord(spoken: String) = viewModelScope.launch {
        settingsRepository.setPersonalDictionary(settingsRepository.current.value.personalDictionary - spoken)
    }

    // ---- history ----

    // Disk failures (full storage, unreadable file) must not take the app down from a button tap.
    fun deleteHistory(entry: TranscriptEntry) = viewModelScope.launch { runCatching { transcriptRepository.delete(entry.id) } }
    fun restoreHistory(entry: TranscriptEntry) = viewModelScope.launch { runCatching { transcriptRepository.restore(entry) } }
    fun clearHistory() = viewModelScope.launch { runCatching { transcriptRepository.clear() } }

    // ---- in-app practice ----

    fun togglePractice() {
        when (engine.state.value) {
            is DictationState.Listening -> if (practiceOwned) engine.finish()
            is DictationState.Finishing -> Unit
            DictationState.Idle -> startPractice()
        }
    }

    private fun startPractice() {
        practice.update { it.copy(error = null) }
        practiceOwned = engine.start(
            listener = { result ->
                practiceOwned = false
                when (result) {
                    is DictationEngine.Result.Success -> practice.update { current ->
                        val joined = if (current.text.isBlank()) result.value else "${current.text.trimEnd()} ${result.value}"
                        current.copy(text = joined, error = null)
                    }
                    is DictationEngine.Result.Failure -> practice.update { it.copy(error = result.error) }
                }
            },
            // Cancelled from elsewhere (bubble, service stop): no result will arrive.
            onCancelled = { practiceOwned = false },
        )
    }

    fun setPracticeText(text: String) = practice.update { it.copy(text = text) }
    fun clearPractice() = practice.update { PracticeUi() }

    fun preloadModel() = engine.preload()

    override fun onCleared() {
        if (practiceOwned) engine.cancel()
        super.onCleared()
    }
}
