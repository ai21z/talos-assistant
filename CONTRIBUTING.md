# Contributing to LOQ-J

Thanks for helping build a secure, local-first RAG CLI! This guide keeps contributions fast, safe, and consistent.

## TL;DR checklist
- Use JDK **21** and the Gradle wrapper (`./gradlew`).
- Keep the app **offline-by-default**. No telemetry. Only localhost (Ollama) calls.
- Run: `./gradlew clean test` before pushing.
- Format & add SPDX headers: `./gradlew spotlessApply`.
- Use **feature branches** + **Conventional Commits**.
- Open a Merge Request (MR) with tests + docs when behavior changes.

## Project goals (north star)
- **Secure & local**: all inference and embeddings run locally by default.
- **Simple CLI UX**: small, composable commands; inline citations.
- **Portable**: Windows/macOS/Linux; Java 21.

## Getting started
```bash
# Clone & build
./gradlew --version
./gradlew clean test

# Install local dist (optional)
./gradlew installDist
build/install/loqj/bin/loqj --version
