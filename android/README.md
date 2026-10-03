# Wisperlow for Android

Wisperlow Android is a native Kotlin/Compose app. Speech recognition, voice
activity detection, transcript cleanup, the personal dictionary, and history
all run on the device.

## Build and verify

Install JDK 17 and Android SDK 35, then run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APKs are written to `app/build/outputs/apk/debug/`. For a smaller,
faster build to install on a phone, run `./gradlew assembleRelease`; without
signing secrets it is signed with the local debug key.

The build passes 42 JVM unit tests and Android lint with no errors, and
produces an arm64 release APK of about 31 MB. The four instrumentation tests
(activity recreation, real-audio VAD, service lifecycle and repeated Parakeet
recognition) need a device or emulator; see below.

## Real-model device verification

The instrumented STT test uses the bundled `test_wavs/en.wav` sample and
requires an installed Parakeet model. Build the debug APK, then provision an
already extracted model and run the test on one authorized device:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
ANDROID_SERIAL=<device-serial> ./scripts/verify-device.sh \
  /path/to/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
```

The helper verifies `tokens.txt` and the three Parakeet ONNX files, streams
the model into the app's private `files/models/<model-id>` directory, writes
the catalog `.installed` marker used by `ModelDownloader`, and removes its
temporary device staging directory when it exits. It trusts the supplied
official extracted model directory and never downloads a model. The test logs
cold load time, decode time, native heap usage, and checks the sample
transcript for the expected JFK phrase, including repeated `ask`, `country`,
and `do` words. The upstream fixture is 24 kHz PCM; the test validates its
WAV metadata and resamples it to the engine's required 16 kHz input.
The helper runs instrumentation directly and leaves the app and model installed.
Grant microphone and overlay permissions first to include the service lifecycle
test; otherwise that test is skipped. Gradle's `connectedDebugAndroidTest`
uninstalls the app after the suite and removes the downloaded model.

For acceptance testing, compare the same recordings containing names, slang,
and punctuation against Samsung Keyboard voice input. Record word error rate,
technical-term accuracy, cold versus warm load/decode latency, and the
resulting `adb shell dumpsys meminfo`, `dumpsys thermalservice`, and battery
statistics. Repeat this on the target Samsung device and at least one lower-
RAM Android 10+ device before making resource or quality claims.

The model layout and `nemo_transducer` configuration follow the official
[sherpa-onnx Parakeet documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html).
The shipped arm64 native libraries were checked with `readelf`: every
`PT_LOAD` segment uses `0x4000` alignment, which is suitable for Android
16 KB page-size devices. Recheck this for any replacement native binaries;
Android's compatibility guidance is in the
[16 KB page-size documentation](https://developer.android.com/guide/practices/page-sizes).

## How it works

Wisperlow is a floating microphone bubble that works next to any keyboard
(Gboard, Samsung Keyboard, …). It is not a keyboard itself.

1. Tap a text field in any app. When the keyboard opens, the bubble docks
   just above it (with the Accessibility service on; otherwise it is always
   visible).
2. Tap the bubble and speak. It turns purple and shows your words as each
   phrase is transcribed during natural pauses.
3. Pause (1.5 s by default, configurable) or tap the bubble to finish. The text
   is typed straight into the field. Hold the bubble to cancel, drag it to move,
   or drop it on the ✕ to hide it until the keyboard opens again.

The first launch walks through every permission with step-by-step help,
including Android's "Allow restricted settings" unlock that sideloaded apps
need before their Accessibility service can be enabled, and ends with a
try-it field.

### Architecture

- `dictation/DictationEngine` owns the microphone, voice detection and
  speech model for every surface. Recording starts immediately while the model
  loads, finished phrases are decoded on a dedicated thread while the user keeps
  talking, and the model is preloaded when a keyboard opens. It is freed after
  5 idle minutes or when Android reports memory pressure, unless "Keep model
  ready" is on.
- `service/DictationService` hosts the bubble as a microphone foreground
  service; the microphone is only open while the bubble is listening.
- `accessibility/WisperlowAccessibilityService` reports keyboard visibility
  and position, and inserts text with a direct set-text action. It falls back to
  paste (marking the clip as sensitive) only for fields that ignore set-text.
- Android does not allow apps to restart a microphone service by themselves
  after a reboot or update, so `RestartReceiver` posts a one-tap notification.

Models download over Wi-Fi unless the user explicitly allows mobile data. They
need about 2.5× the archive size free during extraction, and archives are
checked against the upstream size and SHA-256 before extraction.

| Model | Download | Languages |
| --- | --- | --- |
| Parakeet 0.6B v3 (default) | 490 MB | English + 24 European |
| Parakeet 110M | 105 MB | English (fast, low memory) |
| Parakeet 0.6B v2 | 485 MB | English |

The app targets API 35 with a minimum of API 29 (Android 10). Builds produce
one APK per CPU type plus a universal APK; phones from the last several years
use `app-arm64-v8a-*.apk`.

## Release signing

Release builds are signed when all four environment variables are present:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then build the Play bundle with:

```bash
./gradlew bundleRelease
```

For tagged GitHub Actions builds, configure the matching password/alias secrets
plus `ANDROID_KEYSTORE_BASE64`, containing the base64-encoded keystore. Never
commit the keystore or signing credentials.
