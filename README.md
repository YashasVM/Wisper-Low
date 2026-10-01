# Wisperlow

Wisperlow is a private, local speech-to-text application for desktop and Android. Record with a shortcut, transcribe on-device, and insert the result into the active application.

**Key Features:**
- 🎙️ **Speech-to-Text**: Transcribe audio locally without cloud services
- 🔒 **Private**: All audio processing happens on your device
- ⚡ **Fast**: Optimized for desktop and mobile
- 🔌 **Integrations**: Seamlessly insert transcriptions into any active application

## Getting Started

### Prerequisites

- Rust (stable)
- Bun (JavaScript runtime)
- 4GB RAM minimum

### Development

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

## Architecture

Wisperlow uses:
- **Tauri** for desktop application framework
- **React** for frontend UI
- **Rust** for backend speech processing
- **Silero VAD** for voice activity detection

## Privacy & Security

Wisperlow keeps all speech processing local. Model downloads are initiated by the application and audio is **never** sent to cloud transcription services. Your voice data stays on your device.

- No telemetry tracking
- No external API calls for transcription
- Open source for transparency

## Contributing

Contributions are welcome! Please submit pull requests or open issues for bug reports and feature requests.

## License

MIT License - See LICENSE file for details
