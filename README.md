# LiteRT LM API Server

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)
[![Android API 33+](https://img.shields.io/badge/Android-API%2033%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM_21-purple.svg)](https://kotlinlang.org)

> An Android library that turns on-device LLM inference into an OpenAI-compatible API server, powered by [Google LiteRT LM](https://ai.google.dev/edge/litert).

Turn any Android device into a local AI API endpoint. Run LLMs entirely on-device with LiteRT LM, and expose them through the standard OpenAI Chat Completions API — any client that works with OpenAI will work with this.

## ✨ Features

- 🤖 **On-Device Inference** — LLMs run locally via LiteRT LM, no internet required
- 🔌 **OpenAI-Compatible API** — Drop-in replacement for `/v1/chat/completions`, `/v1/models`, etc.
- 📡 **Streaming SSE** — Real-time token-by-token streaming, just like OpenAI
- 🛠 **Function Calling** — OpenAI-style tool/function calling support
- 🗂 **Session Management** — Multi-session with LRU eviction and auto-timeout
- 🔄 **Dynamic Model Switching** — Hot-swap models at runtime
- 🖼 **Multimodal** — Text + image inputs (base64 / URL / local file)
- ⚡ **Lightweight** — Minimal dependencies, runs as a foreground service

## Architecture

```
┌─────────────────────────────────────────┐
│          Any OpenAI Client               │
│   curl / OpenAI SDK / custom app / ...   │
└──────────────────┬──────────────────────┘
                   │ HTTP / SSE
                   │ :8080
┌──────────────────▼──────────────────────┐
│         LiteRtApiServer (Ktor)           │
│  ┌─────────────┐ ┌───────────────────┐  │
│  │ SessionMgr  │ │ DynamicToolSet    │  │
│  └──────┬──────┘ └────────┬──────────┘  │
│         └────────┬────────┘              │
│           ┌──────▼──────┐                │
│           │ ModelEngine │                │
│           │  Manager    │                │
│           └──────┬──────┘                │
└──────────────────┼──────────────────────┘
                   │
           ┌───────▼───────┐
           │  LiteRT LM    │
           │  (on-device)  │
           └───────────────┘
```

## Quick Start

### 1. Add dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.jenny.litertlm:litertlm-library:1.0.0")
}
```

### 2. Initialize

```kotlin
val manager = ModelEngineManager(context)
manager.loadModel(modelPath)

val server = LiteRtApiServer(manager)
server.start(port = 8080)
```

### 3. Call the API

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4b",
    "messages": [{"role": "user", "content": "Hello!"}],
    "stream": true
  }'
```

## Project Structure

```
litertlm-api-server/
├── library/                        # 📦 Core Library (Apache 2.0)
│   └── src/main/kotlin/dev/jenny/litertlm/
│       ├── engine/                  # ModelEngineManager
│       ├── session/                 # SessionManager
│       ├── tools/                   # DynamicToolSet
│       ├── models/                  # Data classes
│       └── server/                  # LiteRtApiServer (Ktor routes)
├── app/                            # 📱 Android App (Proprietary)
│   └── src/main/kotlin/dev/jenny/litertlm/app/
│       ├── MainActivity.kt
│       ├── ChatActivity.kt
│       ├── ModelApiService.kt
│       └── ...
├── LICENSE                         # Apache 2.0 (for core library)
└── README.md
```

## Supported Models

Any LiteRT LM compatible model (`.task` format):

- Gemma family (1B, 4B, 12B, 27B)
- DeepSeek R1 distilled
- SmolLM
- Custom converted models

## Building

### Core Library (AAR)

```bash
./gradlew :litertlm-library:assembleRelease
```

### APK

```bash
./gradlew :app:assembleRelease
```

Requirements: Android SDK 36, JDK 21, NDK (arm64-v8a).

## License

### Core Library — Apache 2.0

The core library (`library/`) is licensed under the [Apache License 2.0](./LICENSE).

You are free to use, modify, and distribute the library in your own projects, including closed-source applications.

### Android App — Proprietary

The Android application (`app/`) is **not open source**. Pre-built APKs are provided for download. See [app/LICENSE](./app/LICENSE) for details.

---

Made with ❤️ by Jenny
