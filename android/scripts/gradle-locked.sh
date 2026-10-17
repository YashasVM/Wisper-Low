#!/usr/bin/env bash
# Runs this checkout's Gradle wrapper while holding a machine-wide lock, so agents
# working in parallel worktrees take turns instead of starting one Gradle daemon each
# on a machine with little free memory. Usage: scripts/gradle-locked.sh :keyboard-core:test
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
exec flock -w 1800 /tmp/wisperlow-gradle.lock "$here/gradlew" -p "$here" --console=plain "$@"
