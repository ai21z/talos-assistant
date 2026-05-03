# T104 - Managed llama.cpp Windows Backend

Status: Open
Priority: High
Branch: v0.9.0-beta-dev
Source: 2026-05-03 engine backend pivot
Design: `docs/superpowers/specs/2026-05-03-talos-engine-neutral-llama-cpp-design.md`

## Evidence Summary

The selected default backend direction is llama.cpp because it fits Talos'
Windows-first local-agent goal better than vLLM or LocalAI.

Official references:

- llama.cpp releases include Windows artifacts:
  `https://github.com/ggml-org/llama.cpp/releases`
- llama.cpp `llama-server` supports chat-completions-compatible endpoints,
  embeddings, response formats, and function calling:
  `https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md`
- llama.cpp function calling requires correct server/chat-template setup:
  `https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md`

## Classification

Primary taxonomy bucket: `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `TOOL_SURFACE`
- `ACTION_OBLIGATION`
- `VERIFICATION`

Blocker level: release blocker for replacing the default engine

## Architectural Hypothesis

Talos should manage a local `llama-server` process and route chat through the
compat transport. This gives Talos process observability and Windows-first
install control without starting with JNI/native-library complexity.

## Goal

Add a `llama_cpp` backend provider that can run against either a Talos-managed
local `llama-server` process or an already-running local compatible server.

## Scope

- Add `llama_cpp` `ModelEngineProvider`.
- Add config for:
  - managed vs connect-only mode;
  - `llama-server` executable path;
  - model path;
  - host and port;
  - context size;
  - optional chat-template/server flags.
- Implement process launch for Talos-owned server mode.
- Implement health checks.
- Implement model/catalog reporting where available.
- Implement graceful shutdown for Talos-owned processes.
- Fail clearly when binary/model path is missing.
- Use T103 compat transport for chat.

## Non-Goals

- No direct native/JNI integration.
- No automatic model download unless explicitly approved in a later ticket.
- No vLLM or LocalAI provider.
- No full T61-style audit inside this ticket.

## Acceptance Criteria

- Tests prove managed mode launches the configured executable with expected
  arguments using a fake process seam.
- Tests prove connect-only mode never launches a process.
- Tests prove health down states identify missing binary, missing model, failed
  launch, and failed HTTP health separately.
- Tests prove `llama_cpp` provider is discoverable through `EngineRegistry`.
- Manual smoke test can run a local `llama-server` and complete a simple chat
  request.

## Suggested Verification

```powershell
./gradlew.bat test --tests "dev.talos.engine.llamacpp.*" --tests "dev.talos.spi.*" --no-daemon
./gradlew.bat test --no-daemon
```

Manual smoke:

```powershell
talos status
talos --model llama_cpp/<configured-model> "Say hello in one sentence."
```

## Known Risks

- llama.cpp function calling is model/template sensitive. This ticket should
  wire capability and process control, not claim all GGUF models are agent-safe.
- Windows path quoting and process shutdown need focused tests.

## Known Follow-Ups

- T105 makes product setup/status/diagnose backend-neutral.
- T106 runs the focused audit with real llama.cpp.
