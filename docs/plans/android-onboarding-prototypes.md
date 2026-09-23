# Android onboarding prototypes

This document records the onboarding comparison for Packet C of
`android-dictation-ux.md`. The Compose prototypes live in
`android/app/src/main/java/com/wisperlow/mobile/ui/OnboardingFlow.kt`; the
state model is in `OnboardingFlowModel.kt`. Neither prototype is wired into the
existing activity yet.

## Question and recommendation

The question was whether first launch should expose setup as a sequence of
focused screens or as a dense checklist. Both prototypes use the same
resource-backed copy, state model, and callbacks, so the comparison is about
navigation and decision load rather than different behavior.

| Prototype | Shape | Strength | Risk | Recommended use |
| --- | --- | --- | --- | --- |
| `OnboardingStepFlow` | Welcome, microphone, overlay, model, practice, and completion each get a focused panel. | One next action is clear; denial and model failure have an obvious recovery point. | More navigation; returning from Android settings needs an explicit recheck. | First launch. |
| `OnboardingSetupCard` | One card shows required progress and the next unresolved action. Accessibility is an optional subsection of the overlay row. | Works well in Settings and after setup; restart or resume keeps the user in context. | Several states share one surface; a long practice result can make the card tall. | Resumable settings entry point. |

Use the step flow for fresh setup and the card when a user returns to finish or
change setup. Device testing can still reverse that choice if users miss the
current action in either layout. The two composables remain separate so the
choice can be made at integration time without another state-machine fork.

## State and transition contract

`OnboardingReadiness.requiredReady` contains microphone, overlay, and the
selected speech model. Accessibility is deliberately excluded because text can
be copied when automatic insertion is unavailable. `requiredCompletedCount`
also excludes Accessibility, avoiding a progress indicator that can remain
incomplete after the app is usable.

`SettingsReturned(readiness)` must be emitted with a fresh snapshot from
`onResume`. It recalculates the route even after `setupCompleted`; revoking a
required permission therefore reopens the missing step. A denied permission
stays on its current step until a later result or settings recheck arrives.

Model download states are `NotStarted`, `Downloading(progress)`, `Failed`, and
`Ready`. A failed download stays recoverable on the model step; retry resets the
state so the activity can start a new download. The model status does not claim
that a cleanup engine is installed; that separate readiness belongs to the
runtime integration packet.

Practice has explicit `Listening`, `Transcribing`, and `Polishing` stages. A
successful result keeps the original and optional polished text visible while
the user chooses Quick insert or Review first. A cleanup failure retains the
recognized original on the same practice step and can be retried in `Polishing`
without recording again. Every recording or cleanup attempt increments
`practiceAttempt`; late success or failure events from an older attempt are
ignored. A recording failure without an original offers a fresh practice start.
`PracticeSkipRequested` completes the required setup path without Accessibility,
and `PracticeResumeRequested` reopens the practice step.

`AppRestarted` preserves permission/model progress and completed results, while
turning an in-flight practice stage back into `NotStarted`. It never sends a
completed setup back to Welcome. `SetupCompleted` is accepted only after the
required readiness and either a successful or skipped practice.

## Integration callbacks

The eventual activity/view-model adapter should pair each effect with the
corresponding pure event before starting work:

| Callback | Side effect | State event or input |
| --- | --- | --- |
| `onRequestMicrophone` | Launch runtime permission request. | `PermissionResult(Microphone, granted)` from the result callback. |
| `onRequestOverlay` | Open `ACTION_MANAGE_OVERLAY_PERMISSION`. | `SettingsReturned(freshReadiness)` from `onResume`. |
| `onRequestAccessibility` | Open Android Accessibility settings. | `SettingsReturned(freshReadiness)` from `onResume`; this never blocks readiness. |
| `onDownloadModel` | Start or attach to `ModelDownloader`. | `ModelDownloadStarted`, progress, failure, and fresh `Ready` observation. |
| `onRetryModel` | Start the same model download again. | `ModelRetryRequested`, then normal download events. |
| `onStartPractice` | Capture a new practice attempt. | `PracticeStartRequested`, then stage events and a result carrying the attempt. |
| `onStopPractice` | Cancel capture/processing. | Leave the transient stage or dispatch a failure/cancellation result for the same attempt. |
| `onRetryPractice` | Re-run cleanup for the existing original transcript. | `PracticeRetryRequested`; do not start recording. |
| `onSkipPractice` / `onResumePractice` | Move between optional practice and setup completion. | Matching `PracticeSkipRequested` or `PracticeResumeRequested`. |
| `onSelectDeliveryMode` | Persist the user's Quick insert or Review first choice. | `DeliveryModeSelected`. |
| `onCompleteSetup` | Persist setup completion and enter daily use. | `SetupCompleted`. |

The callback object contains no Android or repository types. This lets the
integration layer retain ownership of permissions, model download lifecycle,
service restart, persistence, and cancellation.

## String-resource pass required before integration

`OnboardingCopy` accepts only `@StringRes` IDs; the prototype adds no literal
application copy and does not modify `strings.xml`. The integration pass should
add Android resources for:

- onboarding eyebrow, welcome title/body, progress (`%1$d of %2$d`), and begin;
- microphone, overlay, optional Accessibility, ready, denied, and settings
  action labels;
- speech-model description, download, retry, downloading, percentage, ready,
  and failure (`%1$s`) messages;
- practice title/body, listening, transcribing, polishing, retry, skip, resume,
  original, polished, Quick insert, and Review first labels;
- completion title/body, complete, and done messages;
- recording cancellation, model-loading, cleanup failure, and clipboard-fallback
  messages used by the integrated runtime and review flow.

The Android resources should be translated as a set. The web i18next rule does
not apply to these native Compose screens.

## Verification status

The JVM tests in `OnboardingFlowModelTest.kt` cover denied permissions, fresh
settings rechecks, model failure/retry, practice stages, stale results,
process restart, optional Accessibility, skippable/resumable practice, and
permission revocation after completion. The Compose files are intentionally
isolated; device screenshots, accessibility semantics, large-font behavior,
settings round trips, and real permission/model download checks remain for the
integration and end-to-end packets.
