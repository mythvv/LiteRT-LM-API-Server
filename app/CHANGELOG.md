# Changelog

All notable changes to the pre-built APK releases of **LiteRT LM API Server**.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

---

## [Unreleased] — 2026-06-03

### Added

- **OpenAI-compatible Tool Calling** — request messages now support `tool_calls` and `tool_call_id` fields (`ChatRequest.Message`), matching the OpenAI Chat Completions spec.
- **ProGuard rules for Netty/Ktor** — added keep rules for `io.netty.**` to prevent reflection-based runtime crashes in release builds.

### Changed

- **Model import: OpenDocumentTree only** — removed the legacy "pick single file" flow (`ACTION_OPEN_DOCUMENT`) and `getRealPathFromUri()` heuristic. Models are now imported exclusively via folder scanning (`ACTION_OPEN_DOCUMENT_TREE` + `resolveTreeUriToPath`), giving direct filesystem paths without fragile content:// resolution.
- **Model loading: resolve content:// via /proc/self/fd/** — `ModelEngineManager` now tries to resolve a content URI to a real path through `/proc/self/fd/` first, falling back to copying to internal storage only when necessary. Resolved paths are persisted back to `localPath` so re-resolution is not needed on every startup.
- **Session-based Conversation reuse** — replaced the stateless prefix-caching mechanism with per-session `Conversation` objects via `getOrCreateConversation()`. Stateful sessions only send the last user message (preventing token accumulation from re-sending full history each turn).
- **ChatActivity: local API calls** — switched API endpoint from `${LiteRtApplication.ipAddress.value}` to `127.0.0.1` for more reliable local inference.
- **ChatActivity: modern Activity Result API** — image/audio pickers migrated from `onActivityResult` to `registerForActivityResult(ActivityResultContracts.OpenDocument)`.
- **ChatActivity: Kotlin idiomatic OkHttp** — `RequestBody.create()` → `RequestBody.Companion.toRequestBody()`.

### Fixed

- **Null safety in system prompt parsing** — added smart-cast for `systemMessage?.content` with an explicit `else -> null` branch, eliminating potential `ClassCastException`.
- **sessionId null guard** — token counting and assistant message persistence now check `sessionId != null` before accessing session state, preventing NPE in edge cases.
- **Benchmark token count defaults** — `lastPrefillTokenCount` and `lastDecodeTokenCount` now default to `0` when null.

### Removed

- **Stateless prefix cache** — `getStatelessConversationForPrefix()` and `updateStatelessPrefixCache()` removed; replaced by session-based Conversation reuse.
- **SessionManager.getHistory() / toLiteRtMessages()** — no longer needed; the SDK Conversation object manages its own history.
- **`StoredMessage` import in ModelApiService** — unused after session history refactor.

---

## [1.0.0] — 2026-06-02

### Added

- First public release.
- OpenAI-compatible `/v1/chat/completions` API running entirely on-device via LiteRT LM.
- Support for text, image, and audio modalities.
- Multi-model management (load, unload, configure per model).
- Session management with conversation history.
- Streaming SSE responses.
- Server-sent benchmark metrics (tokens/s, prefill/decode timing).
