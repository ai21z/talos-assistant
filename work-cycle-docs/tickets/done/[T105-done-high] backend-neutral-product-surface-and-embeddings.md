# T105 - Backend-Neutral Product Surface And Embeddings

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: 2026-05-03 engine backend pivot
Design: `docs/superpowers/specs/2026-05-03-talos-engine-neutral-llama-cpp-design.md`

## Evidence Summary

Even with a new chat provider, Talos will still look and behave like an Ollama
wrapper unless setup, status, diagnose, config, env vars, and embeddings are
decoupled.

Current coupling examples:

- `src/main/resources/config/default-config.yaml` defaults to Ollama.
- `src/main/java/dev/talos/app/ui/TerminalFirstRun.java` tells users to install
  Ollama.
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java` installs Ollama and runs
  `ollama pull`.
- `src/main/java/dev/talos/cli/launcher/DiagnoseCmd.java` prints an Ollama
  section.
- `src/main/java/dev/talos/cli/launcher/TopLevelStatusCmd.java` reports
  Ollama host/model.
- `src/main/java/dev/talos/core/embed/EmbeddingsClient.java` directly calls
  Ollama embedding endpoints.
- `src/main/java/dev/talos/core/embed/EmbeddingsFactory.java` fails fast for
  non-Ollama providers.

## Classification

Primary taxonomy bucket: `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `TOOL_SURFACE`
- `TRACE_REDACTION`

Blocker level: release blocker for making llama.cpp the default

## Architectural Hypothesis

Backend neutrality is a product-level invariant, not only a chat-interface
invariant. The setup and diagnostic surfaces must talk in terms of active engine
providers and capability reports.

## Goal

Make Talos user-facing engine surfaces backend-neutral and add a non-Ollama
embedding path or explicit temporary fallback that does not silently call
Ollama.

## Scope

- Update default config toward `llama_cpp` and `engines.*` structure.
- Replace Ollama-specific setup/status/diagnose output with active-provider
  output.
- Keep legacy Ollama settings readable during migration but stop adding new
  code that depends on them.
- Replace `TALOS_OLLAMA_*` assumptions with backend-neutral env var names while
  preserving legacy aliases where needed.
- Add embedding-provider selection that can use compat embeddings or explicitly
  disable embeddings with a clear message.
- Update docs and first-run text.

## Non-Goals

- No automatic GGUF model downloader unless separately approved.
- No removal of legacy Ollama provider in this ticket.
- No full audit.

## Acceptance Criteria

- `talos status` reports active backend, model, host/process state, and
  embedding provider without saying Ollama unless Ollama is actually selected.
- `talos diagnose` uses provider capability and health data.
- First-run/setup no longer says Talos requires Ollama.
- Non-Ollama embedding config does not throw an Ollama-only error.
- Legacy Ollama config still works for users who explicitly select Ollama.
- Tests cover backend-neutral output with fake providers.

## Suggested Verification

```powershell
./gradlew.bat test --tests "dev.talos.cli.launcher.*" --tests "dev.talos.core.embed.*" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```

## Known Risks

- Config migration can break existing users if legacy keys disappear too soon.
  Keep aliases for one beta cycle unless the release decision says otherwise.
- Embedding vector cache identity must include provider/model/dimensions so
  Ollama and compat embeddings cannot be mixed.

## Known Follow-Ups

- T106 validates the product path with the focused llama.cpp audit.
