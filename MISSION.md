# Mission

The user wants to understand **how everything works in the Wisper-Low repo**
(Wisperlow — a private, local speech-to-text desktop/Android app built with
Tauri 2.x + React/TypeScript + Rust), taught as short, self-contained lessons.

Ground rules for all teaching in this workspace:

- Every claim cites a real file + line actually read in the repo.
- Lessons are minimalist, single-file HTML with zero dependencies.
- The primary artifact is `lessons/0001-how-wisperlow-works.html`, which is also
  the file published at `https://a.yash0.in/whisperlow`.
- Success = the user can trace a keypress from global shortcut → microphone →
  VAD → transcription model → pasted text, and knows where each stage lives.
