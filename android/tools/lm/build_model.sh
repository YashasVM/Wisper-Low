#!/usr/bin/env bash
# Reproducible pipeline: corpus-count TSVs -> estimated + quantized + verified WLM1 model.
#
#   android/tools/lm/build_model.sh --counts DIR --out FILE --vocab N \
#     [--min1 C] [--min2 C] [--min3 C]
#
# Gradle never runs directly here: this script calls scripts/gradle-locked.sh (the
# machine-wide lock, see docs/keyboard/ENGINE.md) and passes --init-script pointing at
# compile-model.init.gradle, which adds src/tools/kotlin to the module's main Kotlin
# sources and registers the compileModel task (see the init script's header)
# task. No shared build file is edited and no new dependency is added. The CLI arguments
# travel one per line through a Gradle project property, so spaces and quoting in paths
# survive without shell interpretation.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
android_dir="$(cd "$here/../.." && pwd)"

# The checkout root is informational (paths are used as given); prefer git so worktrees
# and unusual checkouts resolve, falling back to plain path arithmetic.
repo_root=""
if command -v git >/dev/null 2>&1; then
  repo_root="$(git -C "$here" rev-parse --show-toplevel 2>/dev/null || true)"
fi
repo_root="${repo_root:-$(cd "$android_dir/.." && pwd)}"

init_script="$here/compile-model.init.gradle"
[ -f "$init_script" ] || { echo "missing init script: $init_script" >&2; exit 2; }

usage() {
  cat >&2 <<'EOF'
usage: build_model.sh --counts DIR --out FILE --vocab N [--min1 C] [--min2 C] [--min3 C]

  --counts DIR  directory with unigrams.tsv / bigrams.tsv / trigrams.tsv (count_ngrams.py output)
  --out FILE    path of the compact WLM1 model binary (written atomically)
  --vocab N     vocabulary cap, 2..65535
  --min1/2/3 C  optional estimator count floors (default 1: keep every observed n-gram)
EOF
}

model_args=""
while [ $# -gt 0 ]; do
  case "$1" in
    --counts|--out|--vocab|--min1|--min2|--min3)
      [ $# -ge 2 ] || { echo "error: $1 needs a value" >&2; usage; exit 2; }
      model_args+="$1"$'\n'"$2"$'\n'
      shift 2 ;;
    --help|-h)
      usage; exit 0 ;;
    --)
      shift; break ;;
    *)
      echo "error: unknown flag '$1'" >&2; usage; exit 2 ;;
  esac
done
[ "$#" -eq 0 ] || { echo "error: unexpected non-flag arguments: $*" >&2; usage; exit 2; }
[ -n "$model_args" ] || { usage; exit 2; }

# A stale attribute from the caller's environment cannot merge into our arg list
# (the inline assignment below wins anyway).
unset ORG_GRADLE_PROJECT_compileModelArgs

export WISPERLOW_LM_ROOT="$repo_root"
ORG_GRADLE_PROJECT_compileModelArgs="$model_args" \
  "$android_dir/scripts/gradle-locked.sh" -I "$init_script" :keyboard-core:compileModel
