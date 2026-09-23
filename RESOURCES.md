# Resources

High-trust sources used to ground the lessons. Repo files first, docs second.

## Primary (read directly, cited file:line in lessons)

- `README.md` — what Wisperlow is, dev commands, VAD model setup
- `AGENTS.md` — architecture overview, managers, pipeline, CLI flags
- `package.json` — frontend scripts and dependencies (v0.9.5)
- `src-tauri/src/lib.rs` — app entry `run()`, manager init, ~80 Tauri commands,
  events, single-instance, headless path, tray, overlay creation
- `src-tauri/src/cli.rs` — `CliArgs` (clap): all CLI flags
- `src-tauri/src/managers/audio.rs` — `AudioRecordingManager`, mic modes
- `src-tauri/src/managers/model.rs` — `ModelManager`, model registry
- `src-tauri/src/managers/transcription.rs` — `TranscriptionManager`, engines
- `src-tauri/src/managers/history.rs` — `HistoryManager`, sqlite history
- `src/App.tsx` — onboarding flow, post-onboarding init
- `src/stores/settingsStore.ts` — Zustand settings store
- `src-tauri/Cargo.toml` — Rust deps (transcribe-cpp, transcribe-rs, cpal…)

## Secondary (framework docs)

- https://tauri.app — Tauri 2.x commands, events, plugins, single-instance
- https://v2.tauri.app/plugin/store/ — persisted settings store
- https://zustand.docs.pmnd.rs — Zustand `subscribeWithSelector` pattern
- https://www.i18next.com — i18n, English-source locale workflow
