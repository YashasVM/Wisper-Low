package com.wisperlow.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowModelTest {
    @Test
    fun deniedMicrophoneStaysOnPermissionStepUntilTheNextFreshResult() {
        val started = reduceOnboarding(OnboardingState(), OnboardingEvent.Begin)

        val denied = reduceOnboarding(
            started,
            OnboardingEvent.PermissionResult(OnboardingPermission.Microphone, granted = false),
        )
        assertEquals(OnboardingStep.Microphone, denied.step)
        assertEquals(PermissionStatus.Denied, denied.readiness.microphone)

        val granted = reduceOnboarding(
            denied,
            OnboardingEvent.PermissionResult(OnboardingPermission.Microphone, granted = true),
        )
        assertEquals(OnboardingStep.Overlay, granted.step)
    }

    @Test
    fun settingsReturnUsesFreshPermissionSnapshotAndAccessibilityIsOptional() {
        val waitingForOverlay = reduceOnboarding(
            OnboardingState(),
            OnboardingEvent.SettingsReturned(
                OnboardingReadiness(
                    microphone = PermissionStatus.Granted,
                    overlay = PermissionStatus.Denied,
                ),
            ),
        )
        assertEquals(OnboardingStep.Overlay, waitingForOverlay.step)

        val readyForPractice = reduceOnboarding(
            waitingForOverlay,
            OnboardingEvent.SettingsReturned(
                OnboardingReadiness(
                    microphone = PermissionStatus.Granted,
                    overlay = PermissionStatus.Granted,
                    accessibility = PermissionStatus.Denied,
                    model = ModelSetupStatus.Ready,
                ),
            ),
        )
        assertEquals(OnboardingStep.Practice, readyForPractice.step)
        assertTrue(readyForPractice.readiness.requiredReady)
        assertEquals(3, readyForPractice.readiness.requiredCompletedCount)
        assertFalse(readyForPractice.readiness.accessibility == PermissionStatus.Granted)
    }

    @Test
    fun modelFailureCanBeRetriedWithoutLosingPermissionProgress() {
        val ready = stateWithRequiredSetup()
        val failed = reduceOnboarding(ready, OnboardingEvent.ModelDownloadFailed("disk full"))

        assertEquals(OnboardingStep.Model, failed.step)
        assertEquals(ModelSetupStatus.Failed("disk full"), failed.readiness.model)

        val retrying = reduceOnboarding(failed, OnboardingEvent.ModelRetryRequested)
        assertEquals(OnboardingStep.Model, retrying.step)
        assertEquals(ModelSetupStatus.NotStarted, retrying.readiness.model)
        assertEquals(PermissionStatus.Granted, retrying.readiness.microphone)
        assertEquals(PermissionStatus.Granted, retrying.readiness.overlay)
    }

    @Test
    fun practiceTransitionsKeepResultVisibleAndAllowBothDeliveryModes() {
        val listening = reduceOnboarding(
            stateWithRequiredSetup(),
            OnboardingEvent.PracticeStartRequested,
        )
        assertEquals(PracticeStage.Listening, listening.practice)
        assertEquals(1L, listening.practiceAttempt)

        val transcribing = reduceOnboarding(listening, OnboardingEvent.PracticeTranscribing)
        val polishing = reduceOnboarding(transcribing, OnboardingEvent.PracticePolishing)
        val succeeded = reduceOnboarding(
            polishing,
            OnboardingEvent.PracticeSucceeded(
                attempt = polishing.practiceAttempt,
                original = "I I want uh setup",
                polished = "I want setup.",
            ),
        )

        assertEquals(PracticeStage.Succeeded, succeeded.practice)
        assertEquals("I I want uh setup", succeeded.practiceResult?.original)
        assertEquals("I want setup.", succeeded.practiceResult?.polished)

        val quick = reduceOnboarding(
            succeeded,
            OnboardingEvent.DeliveryModeSelected(DeliveryMode.QuickInsert),
        )
        assertEquals(DeliveryMode.QuickInsert, quick.deliveryMode)
        val complete = reduceOnboarding(quick, OnboardingEvent.SetupCompleted)
        assertTrue(complete.setupCompleted)
        assertEquals(OnboardingStep.Complete, complete.step)
    }

    @Test
    fun stalePracticeResultCannotReopenARetriedPractice() {
        val first = reduceOnboarding(
            stateWithRequiredSetup(),
            OnboardingEvent.PracticeStartRequested,
        )
        val retry = reduceOnboarding(first, OnboardingEvent.PracticeRetryRequested)
        val second = reduceOnboarding(retry, OnboardingEvent.PracticeStartRequested)

        val stale = reduceOnboarding(
            second,
            OnboardingEvent.PracticeSucceeded(
                attempt = first.practiceAttempt,
                original = "old result",
                polished = "old result.",
            ),
        )
        assertEquals(PracticeStage.Listening, stale.practice)
        assertNull(stale.practiceResult)

        val failed = reduceOnboarding(
            second,
            OnboardingEvent.PracticeFailed(second.practiceAttempt, "cleanup unavailable"),
        )
        assertEquals(PracticeStage.Failed, failed.practice)
        assertEquals("cleanup unavailable", failed.practiceError)
    }

    @Test
    fun cleanupRetryKeepsOriginalAndDoesNotStartAnotherRecording() {
        val recording = reduceOnboarding(
            stateWithRequiredSetup(),
            OnboardingEvent.PracticeStartRequested,
        )
        val failedCleanup = reduceOnboarding(
            recording,
            OnboardingEvent.PracticeFailed(
                attempt = recording.practiceAttempt,
                message = "cleanup unavailable",
                original = "recognized words",
            ),
        )
        val retryingCleanup = reduceOnboarding(
            failedCleanup,
            OnboardingEvent.PracticeRetryRequested,
        )

        assertEquals(PracticeStage.Polishing, retryingCleanup.practice)
        assertEquals("recognized words", retryingCleanup.practiceResult?.original)
        assertEquals(recording.practiceAttempt + 1L, retryingCleanup.practiceAttempt)

        val finished = reduceOnboarding(
            retryingCleanup,
            OnboardingEvent.PracticeSucceeded(
                attempt = retryingCleanup.practiceAttempt,
                original = "recognized words",
                polished = "Recognized words.",
            ),
        )
        assertEquals(PracticeStage.Succeeded, finished.practice)
        assertEquals("Recognized words.", finished.practiceResult?.polished)
    }

    @Test
    fun skippedPracticeCanBeResumedAndDoesNotRequireAccessibility() {
        val skipped = reduceOnboarding(
            stateWithRequiredSetup(),
            OnboardingEvent.PracticeSkipRequested,
        )
        assertEquals(OnboardingStep.Complete, skipped.step)
        assertEquals(PracticeStage.Skipped, skipped.practice)
        assertTrue(skipped.canCompleteSetup)

        val resumed = reduceOnboarding(skipped, OnboardingEvent.PracticeResumeRequested)
        assertEquals(OnboardingStep.Practice, resumed.step)
        assertEquals(PracticeStage.NotStarted, resumed.practice)

        val completed = reduceOnboarding(resumed, OnboardingEvent.PracticeSkipRequested)
        val setupDone = reduceOnboarding(completed, OnboardingEvent.SetupCompleted)
        assertTrue(setupDone.setupCompleted)

        val restarted = reduceOnboarding(setupDone, OnboardingEvent.AppRestarted)
        assertEquals(OnboardingStep.Complete, restarted.step)
        assertTrue(restarted.setupCompleted)
    }

    @Test
    fun processRestartClearsTransientPracticeButPreservesCompletedSetup() {
        val listening = reduceOnboarding(
            stateWithRequiredSetup(),
            OnboardingEvent.PracticeStartRequested,
        )
        val restarted = reduceOnboarding(listening, OnboardingEvent.AppRestarted)

        assertEquals(OnboardingStep.Practice, restarted.step)
        assertEquals(PracticeStage.NotStarted, restarted.practice)
        assertEquals(listening.practiceAttempt, restarted.practiceAttempt)
    }

    @Test
    fun permissionRevocationAfterCompletionReopensOnlyTheMissingRequiredStep() {
        val complete = OnboardingState(
            step = OnboardingStep.Complete,
            readiness = OnboardingReadiness(
                microphone = PermissionStatus.Granted,
                overlay = PermissionStatus.Granted,
                model = ModelSetupStatus.Ready,
            ),
            practice = PracticeStage.Skipped,
            setupCompleted = true,
        )

        val revoked = reduceOnboarding(
            complete,
            OnboardingEvent.SettingsReturned(complete.readiness.copy(overlay = PermissionStatus.Denied)),
        )
        assertEquals(OnboardingStep.Overlay, revoked.step)
        assertFalse(revoked.readiness.requiredReady)
        assertTrue(revoked.setupCompleted)
    }

    private fun stateWithRequiredSetup(): OnboardingState = OnboardingState(
        step = OnboardingStep.Practice,
        readiness = OnboardingReadiness(
            microphone = PermissionStatus.Granted,
            overlay = PermissionStatus.Granted,
            model = ModelSetupStatus.Ready,
        ),
    )
}
