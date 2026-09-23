package com.wisperlow.mobile.ui

/**
 * The state shared by the first-launch step flow and the resumable setup card.
 *
 * The model intentionally has no Android dependencies. The activity can map its
 * permission and model observations into [OnboardingReadiness], then feed events
 * through [reduceOnboarding]. This keeps settings rechecks and restart behavior
 * deterministic and easy to exercise on the JVM.
 */
enum class OnboardingStep {
    Welcome,
    Microphone,
    Overlay,
    Model,
    Practice,
    Complete,
}

enum class OnboardingPermission {
    Microphone,
    Overlay,
    Accessibility,
}

enum class PermissionStatus {
    Unknown,
    Granted,
    Denied,
}

sealed interface ModelSetupStatus {
    data object NotStarted : ModelSetupStatus

    data class Downloading(val progressPercent: Int? = null) : ModelSetupStatus {
        init {
            require(progressPercent == null || progressPercent in 0..100) {
                "Model progress must be between 0 and 100"
            }
        }
    }

    data object Ready : ModelSetupStatus

    data class Failed(val message: String) : ModelSetupStatus
}

enum class PracticeStage {
    NotStarted,
    Listening,
    Transcribing,
    Polishing,
    Succeeded,
    Failed,
    Skipped,
}

enum class DeliveryMode {
    QuickInsert,
    ReviewFirst,
}

data class OnboardingReadiness(
    val microphone: PermissionStatus = PermissionStatus.Unknown,
    val overlay: PermissionStatus = PermissionStatus.Unknown,
    val accessibility: PermissionStatus = PermissionStatus.Unknown,
    val model: ModelSetupStatus = ModelSetupStatus.NotStarted,
) {
    /** Accessibility improves insertion, but clipboard fallback keeps it optional. */
    val requiredReady: Boolean
        get() = microphone == PermissionStatus.Granted &&
            overlay == PermissionStatus.Granted &&
            model is ModelSetupStatus.Ready

    val requiredCompletedCount: Int
        get() = listOf(
            microphone == PermissionStatus.Granted,
            overlay == PermissionStatus.Granted,
            model is ModelSetupStatus.Ready,
        ).count { it }

    fun permissionStatus(permission: OnboardingPermission): PermissionStatus = when (permission) {
        OnboardingPermission.Microphone -> microphone
        OnboardingPermission.Overlay -> overlay
        OnboardingPermission.Accessibility -> accessibility
    }

    fun withPermission(
        permission: OnboardingPermission,
        status: PermissionStatus,
    ): OnboardingReadiness = when (permission) {
        OnboardingPermission.Microphone -> copy(microphone = status)
        OnboardingPermission.Overlay -> copy(overlay = status)
        OnboardingPermission.Accessibility -> copy(accessibility = status)
    }
}

data class PracticeResult(
    val original: String,
    val polished: String?,
)

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val readiness: OnboardingReadiness = OnboardingReadiness(),
    val practice: PracticeStage = PracticeStage.NotStarted,
    val practiceResult: PracticeResult? = null,
    val practiceError: String? = null,
    val deliveryMode: DeliveryMode = DeliveryMode.ReviewFirst,
    val setupCompleted: Boolean = false,
    val practiceAttempt: Long = 0L,
) {
    val canCompleteSetup: Boolean
        get() = readiness.requiredReady &&
            practice in setOf(PracticeStage.Succeeded, PracticeStage.Skipped)

    /** The next required screen, preserving a finished practice result for review. */
    fun resumeStep(): OnboardingStep = when {
        !readiness.microphone.isGranted() -> OnboardingStep.Microphone
        !readiness.overlay.isGranted() -> OnboardingStep.Overlay
        readiness.model !is ModelSetupStatus.Ready -> OnboardingStep.Model
        setupCompleted -> OnboardingStep.Complete
        practice == PracticeStage.Skipped -> OnboardingStep.Complete
        else -> OnboardingStep.Practice
    }

    /** Re-enter setup after process recreation without leaving a transient spinner behind. */
    fun afterProcessRestart(): OnboardingState {
        val transientPractice = practice in setOf(
            PracticeStage.Listening,
            PracticeStage.Transcribing,
            PracticeStage.Polishing,
        )
        return copy(
            step = resumeStep(),
            practice = if (transientPractice) PracticeStage.NotStarted else practice,
            practiceError = if (transientPractice) null else practiceError,
        )
    }
}

sealed interface OnboardingEvent {
    data object Begin : OnboardingEvent

    data class PermissionResult(
        val permission: OnboardingPermission,
        val granted: Boolean,
    ) : OnboardingEvent

    /** Carries a fresh observation from onResume; cached completion is never trusted. */
    data class SettingsReturned(val readiness: OnboardingReadiness) : OnboardingEvent

    data object ModelDownloadStarted : OnboardingEvent

    data class ModelDownloadProgress(val progressPercent: Int) : OnboardingEvent

    data class ModelDownloadFailed(val message: String) : OnboardingEvent

    data object ModelRetryRequested : OnboardingEvent

    data object PracticeStartRequested : OnboardingEvent

    data object PracticeTranscribing : OnboardingEvent

    data object PracticePolishing : OnboardingEvent

    data class PracticeSucceeded(
        val attempt: Long,
        val original: String,
        val polished: String?,
    ) : OnboardingEvent

    data class PracticeFailed(
        val attempt: Long,
        val message: String,
        val original: String? = null,
    ) : OnboardingEvent

    data object PracticeRetryRequested : OnboardingEvent

    data object PracticeSkipRequested : OnboardingEvent

    data object PracticeResumeRequested : OnboardingEvent

    data class DeliveryModeSelected(val mode: DeliveryMode) : OnboardingEvent

    data object SetupCompleted : OnboardingEvent

    data object AppRestarted : OnboardingEvent
}

fun reduceOnboarding(state: OnboardingState, event: OnboardingEvent): OnboardingState = when (event) {
    OnboardingEvent.Begin -> state.copy(step = state.resumeStep())

    is OnboardingEvent.PermissionResult -> {
        val updated = state.readiness.withPermission(
            event.permission,
            if (event.granted) PermissionStatus.Granted else PermissionStatus.Denied,
        )
        val nextStep = if (event.granted) state.copy(readiness = updated).resumeStep() else state.step
        state.copy(readiness = updated, step = nextStep)
    }

    is OnboardingEvent.SettingsReturned -> state.copy(
        readiness = event.readiness,
        step = state.copy(readiness = event.readiness).resumeStep(),
    )

    OnboardingEvent.ModelDownloadStarted -> state.copy(
        step = OnboardingStep.Model,
        readiness = state.readiness.copy(model = ModelSetupStatus.Downloading()),
    )

    is OnboardingEvent.ModelDownloadProgress -> state.copy(
        step = OnboardingStep.Model,
        readiness = state.readiness.copy(
            model = ModelSetupStatus.Downloading(event.progressPercent),
        ),
    )

    is OnboardingEvent.ModelDownloadFailed -> state.copy(
        step = OnboardingStep.Model,
        readiness = state.readiness.copy(model = ModelSetupStatus.Failed(event.message)),
    )

    OnboardingEvent.ModelRetryRequested -> state.copy(
        step = OnboardingStep.Model,
        readiness = state.readiness.copy(model = ModelSetupStatus.NotStarted),
    )

    OnboardingEvent.PracticeStartRequested -> if (state.readiness.requiredReady) {
        state.copy(
            step = OnboardingStep.Practice,
            practice = PracticeStage.Listening,
            practiceResult = null,
            practiceError = null,
            practiceAttempt = state.practiceAttempt + 1L,
        )
    } else {
        state.copy(step = state.resumeStep())
    }

    OnboardingEvent.PracticeTranscribing -> if (state.practice == PracticeStage.Listening) {
        state.copy(practice = PracticeStage.Transcribing)
    } else {
        state
    }

    OnboardingEvent.PracticePolishing -> if (state.practice == PracticeStage.Transcribing) {
        state.copy(practice = PracticeStage.Polishing)
    } else {
        state
    }

    is OnboardingEvent.PracticeSucceeded -> if (
        event.attempt == state.practiceAttempt &&
        state.practice in setOf(
            PracticeStage.Listening,
            PracticeStage.Transcribing,
            PracticeStage.Polishing,
        )
    ) {
        state.copy(
            step = OnboardingStep.Practice,
            practice = PracticeStage.Succeeded,
            practiceResult = PracticeResult(
                original = event.original,
                polished = event.polished?.takeUnless { it.isBlank() },
            ),
            practiceError = null,
        )
    } else {
        state
    }

    is OnboardingEvent.PracticeFailed -> if (
        event.attempt == state.practiceAttempt &&
        state.practice in setOf(
            PracticeStage.Listening,
            PracticeStage.Transcribing,
            PracticeStage.Polishing,
        )
    ) {
        state.copy(
            step = OnboardingStep.Practice,
            practice = PracticeStage.Failed,
            practiceResult = event.original
                ?.takeUnless { it.isBlank() }
                ?.let { PracticeResult(original = it, polished = null) },
            practiceError = event.message,
        )
    } else {
        state
    }

    OnboardingEvent.PracticeRetryRequested -> {
        val canRetryCleanup = !state.practiceResult?.original.isNullOrBlank()
        state.copy(
            step = OnboardingStep.Practice,
            practice = if (canRetryCleanup) PracticeStage.Polishing else PracticeStage.NotStarted,
            practiceResult = if (canRetryCleanup) state.practiceResult else null,
            practiceError = null,
            practiceAttempt = if (canRetryCleanup) state.practiceAttempt + 1L else state.practiceAttempt,
        )
    }

    OnboardingEvent.PracticeSkipRequested -> if (state.readiness.requiredReady) {
        state.copy(
            step = OnboardingStep.Complete,
            practice = PracticeStage.Skipped,
            practiceResult = null,
            practiceError = null,
        )
    } else {
        state.copy(step = state.resumeStep())
    }

    OnboardingEvent.PracticeResumeRequested -> if (state.practice == PracticeStage.Skipped) {
        state.copy(
            step = OnboardingStep.Practice,
            practice = PracticeStage.NotStarted,
            practiceError = null,
        )
    } else {
        state
    }

    is OnboardingEvent.DeliveryModeSelected -> state.copy(deliveryMode = event.mode)

    OnboardingEvent.SetupCompleted -> if (state.canCompleteSetup) {
        state.copy(step = OnboardingStep.Complete, setupCompleted = true)
    } else {
        state.copy(step = state.resumeStep())
    }

    OnboardingEvent.AppRestarted -> state.afterProcessRestart()
}

private fun PermissionStatus.isGranted(): Boolean = this == PermissionStatus.Granted
