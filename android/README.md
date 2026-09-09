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
