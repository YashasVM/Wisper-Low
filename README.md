<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://img.shields.io/badge/Wisperlow-Local_First_AI_Dictation-FFFFFF?style=for-the-badge&labelColor=050505">
  <img alt="Wisperlow Banner" src="https://img.shields.io/badge/Wisperlow-Local_First_AI_Dictation-050505?style=for-the-badge&labelColor=FFFFFF">
</picture>

### Fast Windows dictation with a tiny overlay, local speech recognition, and cleaned-up output

[![Release](https://img.shields.io/badge/release-v0.1-black?style=flat-square&labelColor=1a1a1a)](https://github.com/YashasVM/Wisper-Low/releases/tag/v0.1)
[![Platform](https://img.shields.io/badge/platform-Windows-blue?style=flat-square&labelColor=1a1a1a)](https://github.com/YashasVM/Wisper-Low)
[![Privacy](https://img.shields.io/badge/privacy-local_first-green?style=flat-square&labelColor=1a1a1a)](#privacy)
[![Status](https://img.shields.io/badge/status-prototype-orange?style=flat-square&labelColor=1a1a1a)](https://github.com/YashasVM/Wisper-Low/issues)

**Local-first** · **Global hotkeys** · **Floating pill UI** · **AI text cleanup**

---

</div>

> [!IMPORTANT]
> **Prototype Release v0.1** Wisperlow is usable today, but it is still early. Expect rough edges with microphone devices, hotkey conflicts, and model latency on slower machines.

## Download

Download the Windows installer from the GitHub release page:

[Download WisperlowSetup-0.1.0.exe](https://github.com/YashasVM/Wisper-Low/releases/download/v0.1/WisperlowSetup-0.1.0.exe)

After downloading:

1. Run `WisperlowSetup-0.1.0.exe`.
2. Follow the installer.
3. Launch **Wisperlow** from the Start Menu.
4. Press `Ctrl+Alt+P` or `Ctrl+Alt+Space`.
5. Speak, press the hotkey again, and Wisperlow pastes the cleaned text into the active field.

> [!NOTE]
> Windows SmartScreen may warn because this prototype is not code-signed yet. Choose **More info** and **Run anyway** if you trust this build.

---

## What Is Wisperlow?

Wisperlow is a Windows desktop dictation app focused on speed, privacy, and polished text. It listens when you trigger the hotkey, transcribes locally, rewrites the rough transcript into clearer text, and inserts it into the app you were already using.

```text
Microphone -> Local Whisper STT -> Text cleanup -> Clipboard paste -> Active app
```

---

## Features

| Feature | Details |
|---|---|
| **Global Dictation** | Trigger dictation from anywhere on Windows with global hotkeys |
| **Local Speech Model** | Bundles a local Whisper model for offline-first transcription |
| **Smart Cleanup** | Improves punctuation, grammar, repeated words, and sentence flow before pasting |
| **Floating Pill UI** | Small black waveform pill while listening and processing |
| **Active App Paste** | Restores focus and inserts text into the currently active text field |
| **Slash Modes** | Supports `/email`, `/professional`, `/casual`, `/slack`, `/short`, and `/raw` |
| **Cancel Flow** | Cancel dictation without pasting using `Ctrl+Alt+Backspace` |
| **Local Optional LLM** | Can use a local Ollama model for stronger rewriting when available |

---

## Quick Start

### 1. Install

Download and run the installer:

[WisperlowSetup-0.1.0.exe](https://github.com/YashasVM/Wisper-Low/releases/download/v0.1/WisperlowSetup-0.1.0.exe)

### 2. Start Dictating

Place your cursor inside any text box, then press:

```text
Ctrl + Alt + P
```

Speak naturally. Press the same hotkey again to stop recording.

### 3. Get Clean Text

Wisperlow processes the transcript, cleans it, and pastes the final result into the active text field.

> [!TIP]
> For the best first test, open Notepad, click into the blank document, press `Ctrl+Alt+P`, speak one short paragraph, then press `Ctrl+Alt+P` again.

---

## Hotkeys

| Hotkey | Action |
|---|---|
| `Ctrl+Alt+P` | Start or stop dictation |
| `Ctrl+Alt+Space` | Alternate start or stop dictation |
| `Ctrl+Alt+Backspace` | Cancel dictation |

If one hotkey is already used by another app, try the alternate hotkey.

---

## Dictation Modes

Say a slash mode at the end of your dictation:

| Mode | Use Case |
|---|---|
| `/email` | Polished email-style wording |
| `/professional` | Clearer workplace tone |
| `/casual` | Natural everyday tone |
| `/slack` | Short chat-friendly message |
| `/short` | Concise rewrite |
| `/raw` | Minimal cleanup |

Example:

```text
can you send over the updated prototype tomorrow morning slash email
```

Wisperlow rewrites it into a cleaner email-style sentence before pasting.

---

## Architecture

```text
Windows Hotkey
    |
    v
Overlay Bubble
    |
    v
Microphone Capture
    |
    v
Local faster-whisper Model
    |
    v
Cleanup + Optional Local LLM Rewrite
    |
    v
Clipboard Paste Into Active App
```

### Runtime Components

| Component | Current Prototype |
|---|---|
| App Shell | Python 3.11 background app |
| Overlay | Tkinter + Pillow-rendered high-DPI pill/blob |
| Speech-to-Text | `faster-whisper` local model |
| Text Cleanup | Deterministic rewrite pipeline with optional Ollama |
| Insertion | Clipboard paste with focus restore |
| Packaging | PyInstaller + Inno Setup |

---

## Privacy

Wisperlow is designed to run locally by default.

| Data | Default Behavior |
|---|---|
| Microphone audio | Captured locally while dictation is active |
| Transcript text | Processed locally |
| Cloud services | Not used by default |
| Ollama rewrite | Optional and local when pointed at `127.0.0.1` |

---

## Developer Setup

Use this only if you want to run from source instead of installing the Windows setup file.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python app.py
```

For a quick smoke test:

```powershell
python app.py --self-test
```

---

## Build Installer

The installer build uses PyInstaller and Inno Setup.

```powershell
.\build.ps1
```

The output is written to:

```text
release\WisperlowSetup-0.1.0.exe
```

---

## Roadmap

- [x] Global hotkey dictation
- [x] Local Whisper transcription
- [x] Floating waveform pill
- [x] Processing pill
- [x] Windows installer
- [x] GitHub release asset
- [ ] Signed Windows installer
- [ ] Better first-run permissions screen
- [ ] Built-in model manager
- [ ] Usage dashboard
- [ ] Faster local rewrite model selection

---

## Notes

- First launch may take longer while the local model warms up.
- Slower CPUs may take longer to process longer dictations.
- If speech dependencies are missing, Wisperlow should show an error instead of crashing.

---

<div align="center">

**Made by [@YashasVM](https://github.com/YashasVM)**

*Dictate anywhere. Keep it local. Paste clean text.*

</div>
