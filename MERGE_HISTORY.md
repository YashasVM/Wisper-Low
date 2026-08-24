# Merge History - Unified Main

This file documents the unification of all branches into main with full history preserved (887+ commits).

- Date: 2026-08-24
- Merge strategy: --no-ff --allow-unrelated-histories
- Branches merged: WIN, fix/windows-startup-audit, codex/android-wisprflow, python-prototype-archive (duplicate)

## Commits


### 1. WIN prototype (5172f574) - Python + WPF installer, model, assets
- Files: app.py, src/WhisperByYashasVM/, installer/, models/, release/


### 2. fix/windows-startup-audit (92281ef0) - cargo config, overlay, tests
- Fixes: Windows startup, onboarding, App.tsx


### 3. codex/android-wisprflow (7ece1d7e) - Android transcript history (14 commits)
- Features: TranscriptRepository, WisperlowAppScreen, Flow theme


### 4. Verification - total commits 887, all branches preserved via merge commits
- Count: git rev-list --count HEAD = 887"n

### 5. Contributions - ensure 30+ commits visible on GitHub profile
- Author: YashasVM <yashasvmtvzzz@gmail.com> verified
- Email verified via GitHub settings


### 6. Push - fast-forward from origin/main (5172f574) to unified main
- Push: git push origin main


### 7. LFS - models/model.bin and release exe via Git LFS
- Tracked in .gitattributes

