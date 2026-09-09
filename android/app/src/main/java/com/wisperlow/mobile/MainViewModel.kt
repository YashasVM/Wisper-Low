package com.wisperlow.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisperlow.mobile.history.TranscriptEntry
import com.wisperlow.mobile.history.TranscriptRepository
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.settings.WisperlowSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class MainUiState(
    val settings: WisperlowSettings = WisperlowSettings(),
    val dictionaryText: String = "",
    val history: List<TranscriptEntry> = emptyList(),
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val transcriptRepository: TranscriptRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var dictionaryLoaded = false
    private var dictionarySaveJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { current -> current.copy(
                    settings = settings,
                    dictionaryText = if (dictionaryLoaded) current.dictionaryText
                    else settings.personalDictionary.entries.joinToString("\n") { (spoken, written) ->
                        "$spoken=$written"
                    },
                ) }
                dictionaryLoaded = true
            }
        }
        viewModelScope.launch {
            transcriptRepository.load()
            transcriptRepository.entries.collect { entries ->
                _uiState.update { it.copy(history = entries) }
            }
        }
    }

    fun setDictionaryText(text: String) {
        _uiState.update { it.copy(dictionaryText = text) }
        // Text fields can emit once per character. Debounce persistence so typing does
        // not keep DataStore and its disk writer busy, while still saving promptly.
        dictionarySaveJob?.cancel()
        dictionarySaveJob = viewModelScope.launch {
            delay(DICTIONARY_SAVE_DEBOUNCE_MS)
            settingsRepository.setPersonalDictionary(SettingsRepository.parseDictionary(text))
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch { transcriptRepository.delete(id) }
    }

    private companion object {
        const val DICTIONARY_SAVE_DEBOUNCE_MS = 350L
    }
}
