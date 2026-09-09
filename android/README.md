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

## Real-model device verification

The instrumented STT test uses the bundled `test_wavs/en.wav` sample and
requires an installed Parakeet model. Build the debug APK, then provision an
already extracted model and run the test on one authorized device:

```bash
./gradlew assembleDebug
ANDROID_SERIAL=<device-serial> ./scripts/verify-device.sh \
  /path/to/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
```

The helper verifies `tokens.txt` and the three Parakeet ONNX files, streams
the model into the app's private `files/models/<model-id>` directory, writes
the catalog `.installed` marker used by `ModelDownloader`, and removes its
temporary device staging directory when it exits. It trusts the supplied
official extracted model directory and never downloads a model. The test logs
cold load time, decode time, native heap
usage, and checks the sample transcript for the expected `tribal`,
`chieftain`, and `gold` words.

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
After local transcription and cleanup, review the text and tap the check mark.
If an app blocks Accessibility insertion, Wisperlow still copies the result to
the clipboard.

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
