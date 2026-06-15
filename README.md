# Wisperlow

Wisperlow is a local-first Windows dictation prototype. Press the hotkey, speak, press it again, and Wisperlow transcribes, cleans up the wording, and pastes the final text into the active text field.

## Download For Windows

The ready-to-install Windows setup file is included in this repository:

[Download WisperlowSetup-0.1.0.exe](release/WisperlowSetup-0.1.0.exe)

After downloading:

1. Run `WisperlowSetup-0.1.0.exe`.
2. Click through the installer.
3. Launch Wisperlow from the Start Menu.
4. Press `Ctrl+Alt+P` or `Ctrl+Alt+Space` to start dictation.
5. Speak, press the hotkey again, and Wisperlow pastes the cleaned text.

Windows may show a SmartScreen warning because this prototype installer is not code-signed yet. Choose **More info** and **Run anyway** if you trust this build.

## What Is Included

- Windows installer with the app bundled
- Local Whisper speech model
- Minimal floating black pill while listening
- Compact loading blob while processing
- Global hotkeys
- Local text cleanup and rewrite pipeline
- Clipboard paste insertion into the active app

## Hotkeys

- `Ctrl+Alt+P`: start or stop dictation
- `Ctrl+Alt+Space`: alternate start or stop dictation
- `Ctrl+Alt+Backspace`: cancel dictation

If one hotkey is already used by another app, try the alternate hotkey.

## Dictation Modes

Say a slash mode at the end of your dictation:

- `/email`
- `/professional`
- `/casual`
- `/slack`
- `/short`
- `/raw`

Example:

```text
can you send over the updated prototype tomorrow morning slash email
```

Wisperlow rewrites it into a cleaner email-style sentence before pasting.

## Privacy

Wisperlow is designed to run locally by default. Audio and transcripts are not sent to cloud services by the app.

Optional local rewriting can use Ollama on `http://127.0.0.1:11434` if you install it separately. That still runs on your machine.

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

## Build Installer

The installer build uses PyInstaller and Inno Setup.

```powershell
.\build.ps1
```

The output is written to:

```text
release\WisperlowSetup-0.1.0.exe
```

## Notes

- First launch may take longer while the local model warms up.
- The app prioritizes local execution and low-latency dictation.
- If speech dependencies are missing, Wisperlow should show an error instead of crashing.
