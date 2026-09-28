# Wisperlow

Wisperlow is a private, local speech-to-text application for desktop and Android. Record with a shortcut, transcribe on-device, and insert the result into the active application.

**Key Features:**
- 🎙️ **Speech-to-Text**: Transcribe audio locally without cloud services
- 🔒 **Private**: All audio processing happens on your device

## Development

Prerequisites:

- Rust (stable)
- Bun

Install dependencies and start the desktop app:

```bash
bun install
bun run tauri dev
```

Useful commands:

```bash
bun run dev       # Frontend-only development
bun run build     # Build the frontend
bun run lint      # Run ESLint
bun run format    # Format frontend and Rust code
bun run tauri build
```

The Silero VAD model is required for local development:

```bash
mkdir -p src-tauri/resources/models
curl -o src-tauri/resources/models/silero_vad_v4.onnx \
  https://blob.handy.computer/silero_vad_v4.onnx
```

Wisperlow keeps speech processing local. Model downloads are initiated by the application and audio is not sent to a cloud transcription service.
