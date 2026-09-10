# Wisperlow for Android

Wisperlow Android is a native Kotlin/Compose app. Speech recognition, voice
activity detection, transcript cleanup, the personal dictionary, and history
all run on the device.

## Build and verify

Install JDK 17 and Android SDK 35, then run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The verified build passes 27 JVM unit tests, Android lint (0 errors), and four
instrumentation tests on an Android 15 x86_64 emulator: activity recreation,
real-audio VAD detection/reset, service startup/shutdown, and repeated Parakeet
recognition. The real-audio suite uses the installed upstream model and has no
skipped tests in that environment. The debug APK is signed for local installation;
release distribution still requires your signing configuration below.

These checks do not establish a battery, thermal, or transcription-accuracy win
over Samsung Keyboard on a physical S24 Ultra. The model is unchanged; the
resource improvements come from lifecycle management, bounded audio capture,
fewer inference workers, and reduced VAD allocations.

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
RAM Android 13+ device before making resource or quality claims.

The model layout and `nemo_transducer` configuration follow the official
[sherpa-onnx Parakeet documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html).
The shipped arm64 native libraries were checked with `readelf`: every
`PT_LOAD` segment uses `0x4000` alignment, which is suitable for Android
16 KB page-size devices. Recheck this for any replacement native binaries;
Android's compatibility guidance is in the
[16 KB page-size documentation](https://developer.android.com/guide/practices/page-sizes).

## Device setup

The app guides the user through microphone, notification, floating-overlay,
and Accessibility permissions. Download the default Parakeet model once, turn
on dictation, focus a text field in any app, and tap the floating bubble.
Wisperlow loads the local STT model lazily on the first dictation and releases
it after 120 seconds idle. Audio capture is limited to one minute per
dictation; all captured frames are sent to review, including quiet starts and
trailing words. After local transcription and cleanup, edit the review text
and tap the check mark. Personal dictionary entries support phrases. The app
does not turn spoken words into built-in voice commands. Text is inserted only
into the focused editable target captured when recording starts through the
Accessibility service; otherwise it is copied to the clipboard.

The current Android target is API 35 with a minimum of API 33 (Android 13).
The two native STT worker threads reduce contention during inference, but
battery, thermal, latency, and low-RAM behavior still require measurement on
real devices.

Model downloads require about 1.2 GB of free space while the compressed archive
and extracted model coexist. Archives are checked against the upstream byte
size and SHA-256 digest before extraction.

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
