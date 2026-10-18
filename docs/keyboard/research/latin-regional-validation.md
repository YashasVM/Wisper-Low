# Latin regional keyboard validation

Validated on 2026-10-17 with the Android debug build from `b78ed58` and
English word-boundary changes from `ca010ae`.

## Automated checks

```bash
cd android
scripts/gradle-locked.sh -q :keyboard-core:test :app:testDebugUnitTest :app:assembleDebug
```

All 272 core tests and 82 app tests passed, with no failures, errors or skips.
The 66 Python dictionary-builder tests passed:

```bash
python3 -m unittest discover -s android/tools/indic -p 'test_build*.py' -q
```

The ARM64 APK contains byte-identical final Latin dictionaries. Native conversion
TSVs are absent from the APK. The builder and input provenance are documented in
[README.latin.md](../../../android/tools/indic/README.latin.md).

## Actual keyboard taps

Installed the x86_64 APK on the isolated Android 15 emulator `openstream-api35`.
Selected Wisperlow as the system IME, selected each input language through the app
settings, then tapped the visible QWERTY keys and committed each word with space.
The test did not paste text or inject it through a helper IME.

| Selected mode | Typed through keyboard | Observed editor text |
| --- | --- | --- |
| Kannada (English letters) | `macha henge edhiya baro le` | `macha henge edhiya baro le` |
| Hindi (Hinglish) | `kya scene hai` | `kya scene hai` |

The Kannada learned file contained those five Latin words and the corresponding
word pairs, including `henge` / `edhiya` and `baro` / `le`. Native output and old
conversion files were not imported. Unit integration tests additionally cover
casing, trust, forgetting, missing data and learned-state reload.

ARM64 debug APK SHA-256:
`e6ed1233ca9b38e1d6191ec76944866b044dc82db705c58b9642e52e48c4b191`.

This verifies Latin preservation and the tested learning paths. It does not measure
broad Kannada/Hindi suggestion accuracy or establish Gboard/iPhone parity. English
benchmark tradeoffs are recorded in [word-boundary-validation.md](word-boundary-validation.md).
The experimental trained ngram model is not included in this APK.
