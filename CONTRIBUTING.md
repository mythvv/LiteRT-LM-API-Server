# Contributing to LiteRT LM API Server

Thank you for your interest! We welcome contributions to the **core library** (`library/`).

## Quick Start

```bash
git clone https://github.com/user/litertlm-api-server.git
cd litertlm-api-server
./gradlew :litertlm-library:assembleDebug
```

Requirements: Android SDK 36, JDK 21, NDK (arm64-v8a).

## Development Workflow

### Branches

- `main` — Stable releases
- `dev` — Development integration
- Feature: `feat/your-feature`
- Fix: `fix/your-fix`

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add multimodal input support
fix: resolve SSE streaming disconnection
docs: update API documentation
refactor: restructure session management
test: add engine module unit tests
chore: upgrade LiteRT LM SDK version
```

### Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Add KDoc for public APIs
- Run `./gradlew ktlintCheck` before submitting

## Scope of Contributions

- ✅ Core library (`library/`) — Open for PRs
- ❌ Android App (`app/`) — Proprietary, not open for contributions

## Pull Request Process

1. Ensure code compiles: `./gradlew build`
2. Ensure code style: `./gradlew ktlintCheck`
3. Update relevant documentation
4. Submit PR with clear description

## Issue Reports

Please include:

- **Environment**: Android version, device, LiteRT LM version
- **Steps to reproduce**
- **Expected vs actual behavior**
- **Logcat output**

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](./LICENSE).
