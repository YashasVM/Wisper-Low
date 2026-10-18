# Word-boundary validation of predictive key changes

The word-level check reduces unwanted changes from the greedy key resolver. It also prevents some useful real-word corrections. These results support the conservative check, but do not establish parity with Gboard or superiority over the legacy engine.

`KeyResolver` predicts each letter from the prefix. That can turn correctly landed keys into a different word before `SuggestionPolicy` sees the literal. For example, `dinner` became `donner`, although the English dictionary counts favor `dinner` by 103 to 1.

`KeyboardEngine.suggest` now reconstructs the rectangle-hit letters from the original canonical touches. If those letters spell a known or learned word, the ordinary decoder and policy evaluate that literal with the same touches. A selected raw literal replaces the visible composing text at commit. Unknown raw words keep the existing resolver path. Decoder weights and policy thresholds are unchanged.

The canonical letter rows have unit key widths. Reconstruction selects the row first, then the nearest key center. Exact row boundaries belong to the lower row, and exact key boundaries belong to the right key, matching `KeyArea`. The service normalizes number-row touches through `Qwerty.touch` before this check.

Immediate backspace restores the visible pre-commit composing text. The existing session trusts that spelling only after the explicit undo. Regression coverage checks that this does not teach a reverse fix automatically.

## Same-input comparisons

Both smart-engine versions use the merged English unigram lists, the same original touches, and one seed. All entries below are percentages. False corrections use correctly typed raw words as their denominator. Recovery uses mistyped raw words.

The diagnostic subset contains the first 40 sentences, or 460 words. Its sentence fingerprint starts with `5bd0a7fdc98f`.

| Metric | Old normal | New normal | Old sloppy | New sloppy |
| --- | ---: | ---: | ---: | ---: |
| Word accuracy without strip picks | 91.96 | 92.61 | 79.35 | 78.04 |
| False corrections | 1.90 | 0.00 | 1.22 | 0.00 |
| Recovery | 78.62 | 76.55 | 68.58 | 65.88 |
| Word accuracy with ideal strip picks | 96.74 | 97.17 | 89.78 | 89.57 |

The held-out subset excludes those 40 sentences. It samples 120 sentences evenly across the remaining 850, covering everyday text, prompts, sample sentences, and texting. It contains 933 words. Its sentence fingerprint starts with `8fd995fa5779`.

| Metric | Old normal | New normal | Old sloppy | New sloppy |
| --- | ---: | ---: | ---: | ---: |
| Word accuracy without strip picks | 90.14 | 91.00 | 73.10 | 73.10 |
| False corrections | 3.84 | 2.15 | 6.97 | 3.33 |
| Recovery | 76.24 | 75.18 | 62.19 | 60.20 |
| Word accuracy with ideal strip picks | 95.82 | 96.14 | 85.32 | 85.85 |

The held-out trace fixes 11 normal and 12 sloppy false corrections. It loses 3 normal and 15 sloppy recoveries, while gaining 3 sloppy recoveries. Other changed outputs remain wrong under both versions. Session learning can also affect later tokens.

## Limits and remaining errors

The held-out legacy `Lexicon` reaches 91.10% normal and 75.67% sloppy accuracy without strip picks. Its false-correction rates are 1.54% and 2.12%. The new smart engine still trails that baseline under sloppy input, despite the improvement over the old smart engine.

Zero false corrections on the first 40 sentences does not generalize. The held-out new-engine trace contains 14 normal and 11 sloppy false corrections. Most change the raw word without a greedy letter change. Examples include unknown slang, one-letter text, and a technical name. Unknown raw words can also suffer greedy changes. This check does not solve those policy and vocabulary gaps.

A unigram model cannot reliably distinguish a deliberate real word from a slip onto another real word. The check preserves more deliberate words and recovers fewer such slips. No exception words were added for either evaluation subset. The held-out subset was measured after implementation without further tuning. It comes from the existing evaluation corpus, not an independent user study.

One seed and two simulated noise presets do not establish real-device typing quality. Latency measurements are machine-specific and are omitted here. The n-gram model is not part of these runs.

## Reproduction record

All runs use 416 misspelling pairs, with fingerprint `c2a4e961d8be548563d8d8558b402e3d35af839008992d8a0325d46cd7bc65b5`. The English asset fingerprint is `c56f158ef85efc21b828caff3368d68333b7816d178400f0d66cf9936e5ef122`.

Normal noise uses sigma 0.25, omission 0.01, insertion 0.005, transposition 0.01, and misspelling 0.05. Sloppy noise uses 0.35, 0.02, 0.01, 0.02, and 0.1 respectively. The CLI uses `--quick --seeds=1`. Each sentence receives the simulator's deterministic sentence-index seed.

The old build copies the current core source into a separate temporary project and replaces only `KeyboardEngine.kt` with its committed version at `0f8571f5bddafa628ee4a11058cbf02295ea1155`. Shared working files are never reverted.

From the repository root, this script regenerates the held-out input and isolated old build:

```bash
python3 - <<'PY'
from pathlib import Path
import shutil
import subprocess

repo = Path.cwd()
android = repo / 'android'
data = android / 'keyboard-core/src/eval/resources/en'
heldout = Path('/tmp/word-boundary-heldout')
heldout.mkdir(exist_ok=True)
sentences = [line.strip() for file in sorted(data.glob('*.txt'))
             for line in file.read_text().splitlines()
             if line.strip() and not line.strip().startswith('#')]
remaining = sentences[40:]
selected = [remaining[i * len(remaining) // 120] for i in range(120)]
(heldout / 'heldout.txt').write_text('\n'.join(selected) + '\n')
for file in data.glob('*misspellings*.tsv'):
    shutil.copy2(file, heldout / file.name)
old = Path('/tmp/word-boundary-old-smart-build')
old.mkdir(exist_ok=True)
for name in ['gradlew', 'gradlew.bat', 'gradle.properties']:
    shutil.copy2(android / name, old / name)
shutil.copytree(android / 'gradle', old / 'gradle', dirs_exist_ok=True)
(old / 'scripts').mkdir(exist_ok=True)
shutil.copy2(android / 'scripts/gradle-locked.sh', old / 'scripts/gradle-locked.sh')
shutil.copytree(android / 'keyboard-core', old / 'keyboard-core',
                ignore=shutil.ignore_patterns('build'), dirs_exist_ok=True)
(old / 'settings.gradle.kts').write_text(
    (android / 'settings.gradle.kts').read_text().replace('include(":app")\n', ''))
(old / 'build.gradle.kts').write_text(
    'plugins { id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false }\n')
engine = 'android/keyboard-core/src/main/kotlin/com/wisperlow/mobile/keyboard/KeyboardEngine.kt'
(old / engine.removeprefix('android/')).write_bytes(subprocess.check_output(
    ['git', 'show', '0f8571f5bddafa628ee4a11058cbf02295ea1155:' + engine]))
PY
```

From `android/`, run each held-out engine through the machine-wide Gradle lock:

```bash
scripts/gradle-locked.sh -q :keyboard-core:typingEval -PevalArgs="--quick --engine=smart --seeds=1 --data=/tmp/word-boundary-heldout --out=/tmp/word-boundary-heldout-smart.json --trace=/tmp/word-boundary-heldout-smart-trace.json"
/tmp/word-boundary-old-smart-build/scripts/gradle-locked.sh -q :keyboard-core:typingEval -PevalArgs="--quick --engine=smart --seeds=1 --data=/tmp/word-boundary-heldout --assets=$PWD/app/src/main/assets --out=/tmp/word-boundary-heldout-old-smart.json --trace=/tmp/word-boundary-heldout-old-smart-trace.json"
scripts/gradle-locked.sh -q :keyboard-core:typingEval -PevalArgs="--quick --engine=baseline --seeds=1 --data=/tmp/word-boundary-heldout --out=/tmp/word-boundary-heldout-baseline.json"
```

For the first-40 comparison, omit `--data` and add `--limit=40` to both smart commands. Keep the old command's explicit `--assets` and choose different output names.

SHA-256 manifest for the measured snapshots:

| Artifact | SHA-256 |
| --- | --- |
| Generated `heldout.txt` bytes | `bb5e160ab15d79b04bb42737ce683491bdc11aa4d57b58d9f5cd9edae710d915` |
| Old `KeyboardEngine.kt` | `6f8bbe50f5f177565069aea5d820d820f94d022c73c85e6512ecedaf5893b2a7` |
| New `KeyboardEngine.kt` | `2e1720c35b62a05144f68c2ecd8f0566dfb9f1c5145d9c7a8b853da227f66e0b` |

The complete temporary manifest is `/tmp/word-boundary-manifest.json`. JSON reports and per-token traces use the paths in the commands above. Evaluation code, English assets, misspelling pairs, and production fixtures were unchanged by this patch.
