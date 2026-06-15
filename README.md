# Wisperlow Prototype

Local-first desktop dictation prototype inspired by Wispr Flow. It runs as a background bubble: press the hotkey, speak, press it again, and it transcribes, cleans, and pastes into the active text field.

## Current Prototype Stack

- Python 3.11 background app with a Tkinter overlay bubble
- Fast local STT through `faster-whisper`
- Always-available deterministic cleanup
- Optional local rewrite through Ollama, disabled by default for latency
- Global hotkeys through the `keyboard` package
- Clipboard paste insertion with active-window restore on Windows
- Tiny monochrome floating pill with live waveform feedback

## Hotkeys

Default hotkeys are configurable in `wisperlow_config.json` after first run:

- `ctrl+alt+p`: start/stop dictation
- `ctrl+alt+space`: alternate start/stop dictation
- `ctrl+alt+backspace`: cancel dictation

If either toggle is claimed by another app, the other one should still work. Change the values in `wisperlow_config.json` if needed.

## Install

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

For a quick non-audio smoke test:

```powershell
python app.py --self-test
python app.py
```

## Local Models

Fastest practical setup for this prototype:

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

The app defaults to `medium.en`, CPU, int8. This avoids CUDA DLL failures such as missing `cublas64_12.dll`/provider DLLs while prioritizing dictation quality over the earlier tiny/fast prototype.

Optional local rewrite:

```powershell
ollama pull qwen3:4b
ollama pull qwen3:0.6b
```

The app prefers `qwen3:4b` for higher-quality rewrites, then uses any good installed local Ollama model it can find. If Ollama is unavailable, it still uses a stronger local rule-based rewrite path.

## Run

```powershell
python app.py
```

Keep the app running in the background. Press `ctrl+alt+space`, speak, press it again, and the app will process and paste into the active field.
The first model download/cache can take longer. After the model is cached and warm-loaded, short dictation should be much faster.

## Windows Installer

The packaged Windows installer is generated at:

```text
release\WisperlowSetup-0.1.0.exe
```

It installs per-user, does not require admin rights, includes the Python runtime and bundled local Whisper speech model, creates Start Menu shortcuts, optionally creates a desktop shortcut, optionally starts with Windows, and can launch Wisperlow after setup.

## Notes

- Audio and transcripts are not sent to cloud services by default.
- Ollama is local-only when pointed at `http://127.0.0.1:11434`.
- If no STT engine is installed, the app still launches and reports the missing dependency instead of crashing.
- The deterministic cleanup path is deliberately fast and runs before any optional LLM call.
