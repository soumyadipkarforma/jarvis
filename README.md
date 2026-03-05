# Jarvis Assistant

A fully offline Android AI voice assistant inspired by Iron Man's Jarvis. Uses on-device wake word detection, speech recognition, language model inference, and text-to-speech — no internet required.

## Features

- **Wake Word Detection** — Say "Jarvis" to activate (Picovoice Porcupine)
- **Offline Speech-to-Text** — Vosk STT for accurate on-device transcription
- **Local LLM** — llama.cpp running Phi-2 (GGUF) for intelligent responses
- **Natural TTS** — Coqui TTS with a deep, Jarvis-like male voice
- **Offline Commands** — Open apps, set alarms/timers, control flashlight, check time/date/battery, adjust volume
- **Foreground Service** — Persistent background listening with minimal battery usage
- **Dark Theme UI** — Sleek conversation interface with chat bubbles

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   MainActivity                   │
│              (Conversation UI + Controls)         │
└──────────────────────┬──────────────────────────┘
                       │ binds
┌──────────────────────▼──────────────────────────┐
│            JarvisForegroundService                │
│         (Background wake word listening)          │
└──┬──────────┬──────────┬──────────┬─────────────┘
   │          │          │          │
   ▼          ▼          ▼          ▼
┌──────┐ ┌────────┐ ┌────────┐ ┌─────────┐
│ Wake │ │  Vosk  │ │ Llama  │ │  Coqui  │
│ Word │ │  STT   │ │  LLM   │ │   TTS   │
│Engine│ │        │ │(Phi-2) │ │         │
└──────┘ └────────┘ └────────┘ └─────────┘
   ▲                    ▲
   │                    │
   └── CommandProcessor ┘
       (Offline commands)
```

## Voice Pipeline

1. **Wake Word** → Porcupine detects "Jarvis" keyword
2. **Record** → Microphone captures user speech
3. **STT** → Vosk transcribes speech to text offline
4. **Command Check** → CommandProcessor checks for device commands
5. **LLM** → If not a command, Phi-2 generates a response via llama.cpp
6. **TTS** → Coqui TTS speaks the response with a deep male voice

## Supported Offline Commands

| Command | Example |
|---------|---------|
| Open app | "Open Chrome", "Launch Settings" |
| Set alarm | "Set alarm 7:30 AM" |
| Set timer | "Set timer 5 minutes" |
| Flashlight | "Turn on flashlight" |
| WiFi | "Open WiFi settings" |
| Bluetooth | "Open Bluetooth settings" |
| Time | "What time is it?" |
| Date | "What's the date?" |
| Battery | "Battery level" |
| Volume | "Volume up", "Volume down" |

## Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34 (API level 34)
- NDK for native library support
- JDK 17

### Model Files

Download and place these model files in the app's files directory:

1. **Vosk Model**: Download `vosk-model-small-en-us-0.15` from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) and place in `assets/model-small-en-us/`
2. **Phi-2 GGUF**: Download `phi-2.Q4_K_M.gguf` from [Hugging Face](https://huggingface.co/TheBloke/phi-2-GGUF) and place in app's files directory
3. **Coqui TTS Model**: Download a Coqui TTS model and place in app's files directory under `tts-model/`
4. **Porcupine**: Get a free access key from [console.picovoice.ai](https://console.picovoice.ai/)

### Build

```bash
# Clone the repository
git clone https://github.com/soumyadipkarforma/jarvis.git
cd jarvis

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Install on device
./gradlew installDebug
```

## Project Structure

```
app/src/main/java/com/jarvis/assistant/
├── JarvisApplication.kt          # App initialization, notification channels
├── MainActivity.kt                # Main UI, service binding, permissions
├── commands/
│   └── CommandProcessor.kt        # Offline command recognition & execution
├── llm/
│   └── LlamaLLMEngine.kt         # llama.cpp JNI wrapper for Phi-2
├── service/
│   └── JarvisForegroundService.kt # Background wake word & voice pipeline
├── stt/
│   └── VoskSpeechToText.kt        # Vosk offline speech recognition
├── tts/
│   └── CoquiTextToSpeech.kt       # Coqui TTS with deep male voice
├── ui/
│   ├── ChatAdapter.kt             # RecyclerView adapter for messages
│   └── MainViewModel.kt           # ViewModel for UI state management
├── util/
│   ├── AudioUtils.kt              # Audio recording & processing utilities
│   └── Models.kt                  # Data classes & enums
└── wakeword/
    └── WakeWordEngine.kt          # Picovoice Porcupine wrapper
```

## Requirements

- **Minimum SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 14 (API 34)
- **Storage**: ~2GB for all models (Phi-2 GGUF ≈ 1.7GB, Vosk ≈ 50MB, TTS ≈ 100MB)
- **RAM**: 4GB+ recommended for LLM inference

## License

This project is for educational purposes. Individual components have their own licenses:
- Picovoice Porcupine: [Apache 2.0](https://github.com/Picovoice/porcupine/blob/master/LICENSE)
- Vosk: [Apache 2.0](https://github.com/alphacep/vosk-api/blob/master/COPYING)
- llama.cpp: [MIT](https://github.com/ggerganov/llama.cpp/blob/master/LICENSE)
- Coqui TTS: [MPL 2.0](https://github.com/coqui-ai/TTS/blob/dev/LICENSE.txt)