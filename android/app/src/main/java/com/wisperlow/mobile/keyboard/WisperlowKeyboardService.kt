package com.wisperlow.mobile.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wisperlow.mobile.MainActivity
import com.wisperlow.mobile.dictation.DictationEngine
import com.wisperlow.mobile.dictation.DictationState
import com.wisperlow.mobile.history.TranscriptRepository
import com.wisperlow.mobile.settings.KeyboardPrefs
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.ui.WisperlowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wisperlow as a full keyboard: QWERTY with autocorrect, suggestions and emoji,
 * and on-device dictation that types into the field as you speak. Android hands an
 * input method a direct connection to the focused field, so none of this needs
 * Accessibility, overlays or a background service.
 */
@AndroidEntryPoint
class WisperlowKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    @Inject lateinit var engine: DictationEngine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var transcriptRepository: TranscriptRepository
    @Inject lateinit var lexiconRepository: LexiconRepository

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = MutableStateFlow(KeyboardUi())
    private var inputView: View? = null

    private val field = ConnectionField()
    private val typing = TypingSession(field, { lexiconRepository.lexicon.value })
    private var suggestJob: Job? = null
    private var prefs = KeyboardPrefs()
    private var lastShiftTap = 0L

    /** True while the engine session in progress was started from this keyboard, not the app's practice area. */
    private var ownsSession: Boolean
        get() = owned.value
        set(value) {
            owned.value = value
        }

    /**
     * Observable so the voice sheet appears the moment a session is ours: the engine
     * already reports Listening from inside start(), before start() returns.
     */
    private val owned = MutableStateFlow(false)
    private var holdToTalk = false
    /** Text around the cursor when dictation started; the transcript is fitted between them. */
    private var voiceBefore = ""
    private var voiceAfter = ""
    /** Dictated text currently shown as composing text in the field. */
    private var streaming = false
    private var lastDictation: String? = null
    private var dismissedClip: String? = null

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val clipHistory by lazy { ClipboardHistory.fromJson(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CLIPS, null)) }

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lexiconRepository.load()
        clipHistory.prune(System.currentTimeMillis())
        ui.update { it.copy(recentEmoji = loadRecentEmoji(), clips = clipHistory.entries) }
        // As the active keyboard, Wisperlow may read the clipboard; keep a history for the panel.
        clipboard?.addPrimaryClipChangedListener(clipListener)
        scope.launch {
            settingsRepository.current.collect { settings ->
                prefs = settings.keyboard
                ui.update {
                    it.copy(
                        numberRow = settings.keyboard.numberRow,
                        heightScale = if (it.resizing) it.heightScale else settings.keyboard.heightScale,
                    )
                }
                applyFieldOptions()
            }
        }
        // Words from the personal dictionary are the user's own, so suggest them and never "fix" them.
        scope.launch {
            combine(lexiconRepository.lexicon, settingsRepository.current) { lexicon, settings -> lexicon to settings.personalDictionary }
                .collect { (lexicon, dictionary) ->
                    lexicon ?: return@collect
                    dictionary.values.flatMap { it.split(' ') }.filter { it.length > 1 }.forEach(lexicon::trust)
                    refreshSuggestions()
                }
        }
        // Stream dictation into the field as composing text, the way the words are being heard.
        scope.launch {
            engine.state.collect { state ->
                if (!ownsSession || !streaming) return@collect
                val partial = when (state) {
                    is DictationState.Listening -> state.partial
                    is DictationState.Finishing -> state.partial
                    DictationState.Idle -> return@collect
                }
                if (partial.isNotBlank()) currentInputConnection?.setComposingText(SmartSpacing.fit(voiceBefore, voiceAfter, partial), 1)
            }
        }
    }

    override fun onCreateInputView(): View {
        // Compose looks for its owners on the window's root view, which belongs to the IME window here.
        window.window?.decorView?.let { root ->
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                WisperlowTheme {
                    val dictation by engine.state.collectAsStateWithLifecycle()
                    val state by ui.collectAsStateWithLifecycle()
                    val mine by owned.collectAsStateWithLifecycle()
                    KeyboardScreen(
                        ui = state,
                        dictation = if (mine) dictation else DictationState.Idle,
                        level = { engine.level.value },
                        actions = actions,
                    )
                }
            }
        }.also { inputView = it }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        lexiconRepository.load()
        if (!restarting) {
            typing.reset()
            lastDictation = null
            val kind = fieldKind(info)
            ui.update {
                it.copy(
                    field = kind,
                    page = when (kind) {
                        FieldKind.NUMBER -> Page.NUMBERS
                        FieldKind.PHONE -> Page.PHONE
                        else -> Page.LETTERS
                    },
                    enter = enterAction(info),
                    shift = ShiftState.OFF,
                    review = null,
                    error = null,
                    canUndoDictation = false,
                    suggestions = Suggestions.None,
                    emojiHints = emptyList(),
                )
            }
        }
        applyFieldOptions()
        updateShift()
        refreshSuggestions()
        refreshClip(fresh = false)
        if (settingsRepository.current.value.keepModelLoaded) engine.preload()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // The field is gone; anything still being said has nowhere to go.
        if (ownsSession) engine.cancel()
        typing.reset()
        if (ui.value.resizing) endResize()
        ui.update { it.copy(tools = false, page = if (it.page == Page.CLIPBOARD) Page.LETTERS else it.page) }
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (streaming) return
        val word = typing.composing
        if (word.isNotEmpty()) {
            // Updates arrive late, so judge by the field as it is now: did the cursor leave the word?
            val moved = newSelStart != newSelEnd ||
                (candidatesEnd >= 0 && candidatesEnd != newSelEnd) ||
                (candidatesEnd < 0 && !field.before(word.length).endsWith(word))
            if (!moved) return
            typing.reset()
        }
        updateShift()
        refreshSuggestions()
    }

    override fun onDestroy() {
        if (ownsSession) engine.cancel()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        scope.cancel()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engine.onTrimMemory(level)
    }

    // ---- typing ----

    private val keyListener = object : KeyListener {
        override fun onDown(key: Key) {
            feedback(key)
            if (ui.value.canUndoDictation) ui.update { it.copy(canUndoDictation = false) }
            lastDictation = null
        }

        override fun onText(text: String) {
            typing.type(text)
            afterEdit()
        }

        override fun onKey(key: Key) {
            when (key.kind) {
                KeyKind.SHIFT -> toggleShift()
                KeyKind.SPACE -> {
                    typing.space()
                    if (ui.value.page == Page.SYMBOLS || ui.value.page == Page.MORE_SYMBOLS) ui.update { it.copy(page = Page.LETTERS) }
                    afterEdit()
                }
                KeyKind.ENTER -> enter()
                KeyKind.PAGE -> {
                    ui.update { it.copy(page = key.page ?: Page.LETTERS) }
                    updateShift()
                }
                KeyKind.EMOJI -> {
                    typing.finishWord()
                    ui.update { it.copy(page = Page.EMOJI) }
                }
                else -> Unit
            }
        }

        override fun onBackspace(repeats: Int) {
            if (repeats >= WORD_DELETE_AFTER) typing.deleteWord() else typing.backspace()
            afterEdit()
        }

        override fun onCursor(steps: Int) {
            typing.finishWord()
            val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
            inputView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        override fun onSpaceLongPress() {
            inputView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private fun afterEdit() {
        updateShift()
        refreshSuggestions()
        lexiconRepository.scheduleSave()
    }

    private fun toggleShift() {
        val now = System.currentTimeMillis()
        val current = ui.value.shift
        val next = when {
            // Double-tap locks capitals, as on every phone keyboard.
            current == ShiftState.ONCE && now - lastShiftTap < DOUBLE_TAP_MS -> ShiftState.LOCKED
            current == ShiftState.OFF -> ShiftState.ONCE
            else -> ShiftState.OFF
        }
        lastShiftTap = now
        ui.update { it.copy(shift = next) }
    }

    /** One capital at the start of a sentence; caps lock stays until turned off. */
    private fun updateShift() {
        val state = ui.value
        if (state.shift == ShiftState.LOCKED || state.page != Page.LETTERS) return
        val info = currentInputEditorInfo
        val wantsCap = prefs.autoCapitalize && typing.composing.isEmpty() && info != null && info.inputType != InputType.TYPE_NULL &&
            (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        val next = if (wantsCap) ShiftState.ONCE else ShiftState.OFF
        if (next != state.shift) ui.update { it.copy(shift = next) }
    }

    private fun enter() {
        typing.finishWord()
        val info = currentInputEditorInfo
        val action = ui.value.enter
        when {
            action != EnterAction.NEWLINE && info != null -> currentInputConnection?.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
            info == null || info.inputType == InputType.TYPE_NULL -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            else -> currentInputConnection?.commitText("\n", 1)
        }
        afterEdit()
    }

    private fun refreshSuggestions() {
        val options = typing.options
        val state = ui.value
        if (!options.suggestions || state.page == Page.EMOJI || state.page == Page.CLIPBOARD) {
            if (state.suggestions != Suggestions.None || state.emojiHints.isNotEmpty()) {
                ui.update { it.copy(suggestions = Suggestions.None, emojiHints = emptyList()) }
            }
            return
        }
        val lexicon = lexiconRepository.lexicon.value ?: return
        val word = typing.composing
        val previous = typing.previousWord()
        suggestJob?.cancel()
        suggestJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                if (word.isNotEmpty()) {
                    lexicon.suggest(word, previous).let { if (options.autoCorrect) it else it.copy(autoCorrect = null) }
                } else {
                    Suggestions(lexicon.predict(previous))
                }
            }
            ui.update { it.copy(suggestions = result, emojiHints = Emoji.forWord(word.ifEmpty { previous.orEmpty() })) }
        }
    }

    private fun applyFieldOptions() {
        val info = currentInputEditorInfo
        val kind = fieldKind(info)
        val inputType = info?.inputType ?: 0
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val text = kind == FieldKind.TEXT && inputType != InputType.TYPE_NULL
        // Chat apps such as Instagram set NO_SUGGESTIONS on ordinary message boxes. Like Gboard,
        // treat it as a hint and still help with typing; password fields stay off regardless.
        Log.d(TAG, "Field ${info?.packageName}: inputType=0x${Integer.toHexString(inputType)} kind=$kind")
        val private = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        val names = variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
            variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        typing.options = TypingOptions(
            suggestions = text || kind == FieldKind.EMAIL || kind == FieldKind.URI,
            autoCorrect = text && prefs.autoCorrect && !names,
            learn = text && !private,
            doubleSpacePeriod = text && prefs.doubleSpacePeriod,
        )
    }

    private fun feedback(key: Key) {
        if (prefs.haptics) inputView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        if (prefs.sound) {
            val effect = when (key.kind) {
                KeyKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            getSystemService(AudioManager::class.java)?.playSoundEffect(effect, -1f)
        }
    }

    // ---- emoji and clipboard ----

    private fun pickEmoji(emoji: String) {
        inputView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        typing.insert(emoji)
        val recent = (listOf(emoji) + ui.value.recentEmoji.filter { it != emoji }).take(MAX_RECENT_EMOJI)
        ui.update { it.copy(recentEmoji = recent) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_RECENT_EMOJI, recent.joinToString(" ")).apply()
        afterEdit()
    }

    private fun loadRecentEmoji(): List<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RECENT_EMOJI, null)
            ?.split(' ')?.filter { it.isNotBlank() }.orEmpty()

    private fun onClipChanged() {
        val text = readClip()
        if (text != null && ui.value.field != FieldKind.PASSWORD) {
            clipHistory.add(text, System.currentTimeMillis())
            saveClips()
        }
        refreshClip(fresh = true)
    }

    private fun saveClips() {
        ui.update { it.copy(clips = clipHistory.entries) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CLIPS, clipHistory.toJson()).apply()
    }

    /** The current clip as text, unless the copying app marked it sensitive (passwords, codes). */
    private fun readClip(): String? = runCatching {
        val description = clipboard?.primaryClipDescription ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= 33 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            return@runCatching null
        }
        clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun endResize() {
        val scale = ui.value.heightScale
        ui.update { it.copy(resizing = false) }
        scope.launch { settingsRepository.setKeyboard(prefs.copy(heightScale = scale)) }
    }

    /** Offers text copied in the last few minutes, the way Gboard does; never in password fields. */
    private fun refreshClip(fresh: Boolean) {
        val text = runCatching {
            val description = clipboard?.primaryClipDescription ?: return@runCatching null
            if (!fresh && System.currentTimeMillis() - description.timestamp > CLIP_FRESH_MS) return@runCatching null
            if (Build.VERSION.SDK_INT >= 33 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
                return@runCatching null
            }
            clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CLIP && it != dismissedClip }
        val allowed = ui.value.field != FieldKind.PASSWORD
        ui.update { it.copy(clip = if (allowed) text else null) }
    }

    // ---- dictation ----

    private fun toggleDictation() {
        holdToTalk = false
        when (engine.state.value) {
            is DictationState.Listening -> if (ownsSession) engine.finish()
            is DictationState.Finishing -> Unit
            DictationState.Idle -> startDictation()
        }
    }

    private fun startDictation() {
        typing.finishWord()
        val connection = currentInputConnection
        voiceBefore = connection?.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
        voiceAfter = connection?.getTextAfterCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
        streaming = !settingsRepository.current.value.reviewBeforeInsert
        ui.update { it.copy(review = null, error = null, canUndoDictation = false, page = if (it.page == Page.EMOJI) Page.LETTERS else it.page) }
        ownsSession = true
        ownsSession = engine.start(
            listener = { result ->
                ownsSession = false
                when (result) {
                    is DictationEngine.Result.Success -> onTranscript(result.value)
                    is DictationEngine.Result.Failure -> {
                        clearStream()
                        ui.update { it.copy(error = result.error) }
                    }
                }
            },
            onCancelled = {
                ownsSession = false
                clearStream()
            },
        )
        if (!ownsSession) streaming = false
    }

    private fun onTranscript(text: String) {
        val command = text.lowercase().trim(' ', '.', '!', '?', ',')
        if (command in UNDO_COMMANDS) {
            clearStream()
            undoDictation()
            return
        }
        if (!streaming) {
            ui.update { it.copy(review = text) }
            return
        }
        val fitted = SmartSpacing.fit(voiceBefore, voiceAfter, text)
        currentInputConnection?.let { ic ->
            ic.beginBatchEdit()
            ic.setComposingText(fitted, 1)
            ic.finishComposingText()
            ic.endBatchEdit()
        }
        streaming = false
        delivered(fitted, text)
    }

    private fun clearStream() {
        if (!streaming) return
        streaming = false
        currentInputConnection?.let { ic ->
            ic.beginBatchEdit()
            ic.setComposingText("", 1)
            ic.finishComposingText()
            ic.endBatchEdit()
        }
    }

    private fun insertReview() {
        val text = ui.value.review ?: return
        ui.update { it.copy(review = null) }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
        val fitted = SmartSpacing.fit(before, after, text)
        if (fitted.isEmpty() || !ic.commitText(fitted, 1)) return
        delivered(fitted, text)
    }

    private fun delivered(inserted: String, transcript: String) {
        lastDictation = inserted
        ui.update { it.copy(canUndoDictation = true) }
        afterEdit()
        if (settingsRepository.current.value.historyEnabled) {
            scope.launch {
                runCatching { transcriptRepository.add(transcript) }
                    .onFailure { Log.e(TAG, "History save failed", it) }
            }
        }
    }

    /** Takes back the last dictation if it is still right before the cursor. */
    private fun undoDictation() {
        val last = lastDictation
        lastDictation = null
        ui.update { it.copy(canUndoDictation = false) }
        if (last != null && field.before(last.length) == last) field.deleteBefore(last.length)
        afterEdit()
    }

    private val actions = KeyboardActions(
        keys = keyListener,
        onSuggestion = { word ->
            inputView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            typing.pick(word)
            afterEdit()
        },
        onEmoji = ::pickEmoji,
        onPaste = {
            val clip = ui.value.clip
            if (clip != null) {
                typing.insert(clip)
                dismissedClip = clip
                ui.update { it.copy(clip = null) }
                afterEdit()
            }
        },
        onDismissClip = {
            dismissedClip = ui.value.clip
            ui.update { it.copy(clip = null) }
        },
        onMicTap = {
            inputView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleDictation()
        },
        onMicHold = {
            if (engine.state.value == DictationState.Idle) {
                inputView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                startDictation()
                holdToTalk = ownsSession
            }
        },
        onMicRelease = {
            if (holdToTalk && ownsSession && engine.state.value is DictationState.Listening) engine.finish()
            holdToTalk = false
        },
        onCancelVoice = { if (ownsSession) engine.cancel() },
        onUndoDictation = ::undoDictation,
        onInsertReview = ::insertReview,
        onDiscardReview = { ui.update { it.copy(review = null) } },
        onDismissError = { ui.update { it.copy(error = null) } },
        onOpenApp = {
            ui.update { it.copy(tools = false) }
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onToggleTools = { ui.update { it.copy(tools = !it.tools) } },
        onOpenPage = { page ->
            typing.finishWord()
            clipHistory.prune(System.currentTimeMillis())
            ui.update { it.copy(page = page, tools = false, clips = clipHistory.entries) }
            refreshSuggestions()
        },
        onStartResize = { ui.update { it.copy(resizing = true, tools = false) } },
        onResize = { scale ->
            ui.update { it.copy(heightScale = scale.coerceIn(KeyboardPrefs.MIN_HEIGHT_SCALE, KeyboardPrefs.MAX_HEIGHT_SCALE)) }
        },
        onResetSize = { ui.update { it.copy(heightScale = 1f) } },
        onEndResize = ::endResize,
        onPasteText = { text ->
            inputView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            typing.insert(text)
            afterEdit()
        },
        onTogglePin = { text ->
            clipHistory.togglePin(text)
            saveClips()
        },
        onDeleteClip = { text ->
            clipHistory.remove(text)
            saveClips()
        },
        onClearClips = {
            clipHistory.clear()
            saveClips()
        },
    )

    /** The focused field, reached through whichever InputConnection is current. */
    private inner class ConnectionField : EditorField {
        override fun before(count: Int) = currentInputConnection?.getTextBeforeCursor(count, 0)?.toString().orEmpty()
        override fun after(count: Int) = currentInputConnection?.getTextAfterCursor(count, 0)?.toString().orEmpty()
        override fun setComposing(text: String) {
            currentInputConnection?.setComposingText(text, 1)
        }
        override fun commit(text: String) {
            currentInputConnection?.commitText(text, 1)
        }
        override fun finishComposing() {
            currentInputConnection?.finishComposingText()
        }
        override fun deleteBefore(count: Int) {
            currentInputConnection?.deleteSurroundingText(count, 0)
        }
        // A key event lets the app handle selections, emoji and its own delete behaviour.
        override fun backspaceKey() = sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        override fun batch(block: () -> Unit) {
            val ic = currentInputConnection
            ic?.beginBatchEdit()
            try {
                block()
            } finally {
                ic?.endBatchEdit()
            }
        }
    }

    private fun fieldKind(info: EditorInfo?): FieldKind {
        val type = info?.inputType ?: return FieldKind.TEXT
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> FieldKind.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                -> FieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
    }

    private fun enterAction(info: EditorInfo?): EnterAction {
        info ?: return EnterAction.NEWLINE
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.NEWLINE
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            else -> EnterAction.NEWLINE
        }
    }

    private companion object {
        const val TAG = "WisperlowKeyboard"
        const val CONTEXT_CHARS = 64
        const val DOUBLE_TAP_MS = 350L
        const val CLIP_FRESH_MS = 3 * 60_000L
        const val MAX_CLIP = 2_000
        const val MAX_RECENT_EMOJI = 32
        const val PREFS = "keyboard"
        const val KEY_RECENT_EMOJI = "recent_emoji"
        const val KEY_CLIPS = "clipboard_history"
        val UNDO_COMMANDS = setOf("scratch that", "delete that", "undo that")
    }
}
