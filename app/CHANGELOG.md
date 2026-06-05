# Changelog

All notable changes to the pre-built APK releases of **LiteRT LM API Server**.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

---

## [1.1.1] — 2026-06-05

### Fixed

- **Empty response after tool execution** — added a deterministic fallback builder that extracts tool results from the message history when the model returns empty text, ensuring the user always sees meaningful output.
- **Lifecycle crash during tool calls** — `requestSummaryAfterTool` is now launched inside `lifecycleScope` with an `isDestroyed` / `isFinishing` guard, preventing crashes when the activity is finishing.
- **False "network error" on deliberate cancellation** — introduced a `deliberatelyCancelled` flag so that user-initiated stream cancellations no longer trigger the error toast.
- **Missing chat history update after tool result** — when a fallback text is generated, the in-memory message list and recycler view are now updated immediately so the UI stays in sync.

### Changed

- **Gemma 4 model detection** — switched from manual string checks to a regex pattern (`gemma[-_ ]?4`), making detection more robust across different naming conventions.
- **Default location for weather tool** — if the user doesn't specify a city, the tool now defaults to "New York" instead of failing.
- **Default URL for web fetch tool** — if the user doesn't specify a URL, the tool now defaults to Google News homepage instead of failing.

---

## [1.1.0] — 2026-06-04

### Added

- **Built-in tool auto-injection** — when no `tools` array is provided in the request, the server automatically injects the built-in tool set, so simple clients can use tool calling without specifying tool definitions.
- **Tool call round limit** — client-side safety guard: tool-calling loops are capped at 5 rounds to prevent infinite recursion.
- **Conversation history accumulation** — `ChatActivity` now accumulates full multi-turn message history (including tool call/result exchanges) and sends it with every request, eliminating the need for server-side session state.

### Changed

- **Stateless API requests** — removed `session_id` from all client requests. The app now sends the complete conversation context in each request instead of relying on server-side sessions. This simplifies the server architecture and avoids stale session resource leaks.
- **Tool call deduplication** — added per-message dedup tracking (`lastExecutedToolCalls`) to prevent the same tool call from being executed twice on UI redraws.
- **Improved tool integration** — `ModelApiService` now routes client-provided tools through the same `DynamicToolSet` pipeline, unifying handling of user-defined and built-in tools.

### Removed

- **Session cleanup on exit** — the `onDestroy` HTTP DELETE to `/v1/sessions/{sid}` is no longer needed since sessions are not used.
- **Session ID reset after summarization** — removed the UUID regeneration that followed conversation summarization.

## [1.0.0] — 2026-06-02

### Added

- First public release.
- OpenAI-compatible `/v1/chat/completions` API running entirely on-device via LiteRT LM.
- Support for text, image, and audio modalities.
- Multi-model management (load, unload, configure per model).
- Session management with conversation history.
- Streaming SSE responses.
- Server-sent benchmark metrics (tokens/s, prefill/decode timing).
