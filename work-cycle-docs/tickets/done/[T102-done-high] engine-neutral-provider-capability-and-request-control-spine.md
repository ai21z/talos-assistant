# T102 - Engine-Neutral Provider Capability And Request-Control Spine

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: 2026-05-03 engine backend pivot
Design: `docs/superpowers/specs/2026-05-03-talos-engine-neutral-llama-cpp-design.md`

## Evidence Summary

- Talos has an engine SPI, but the request and capability shape still reflects
  the current Ollama implementation.
- `ChatRequest` carries messages and tools, but no provider-neutral fields for
  required tool choice, named tool choice, JSON object output, JSON schema
  output, or provider-body debug tags.
- `Capabilities` has only `nativeTools` for action-control capability.
- Current action-loop reliability work needs deterministic knowledge about
  provider controls instead of checking backend names.

Relevant code:

- `src/main/java/dev/talos/spi/types/ChatRequest.java`
- `src/main/java/dev/talos/spi/types/Capabilities.java`
- `src/main/java/dev/talos/spi/EngineRegistry.java`
- `src/main/java/dev/talos/core/llm/LlmClient.java`
- `src/main/java/dev/talos/runtime/toolcall/BackendToolProfile.java`

## Classification

Primary taxonomy bucket: `TOOL_SURFACE`

Secondary buckets:

- `ACTION_OBLIGATION`
- `CURRENT_TURN_FRAME`
- `UNSUPPORTED_CAPABILITY`

Blocker level: release blocker for the engine pivot

## Architectural Hypothesis

Talos should not encode backend control as Ollama-specific assumptions. The
runtime needs provider-neutral request controls and provider-reported
capabilities so it can choose the safest enforcement strategy for each turn.

## Goal

Add the neutral spine that later llama.cpp, vLLM, LocalAI, and legacy Ollama
providers can report through without leaking provider-specific fields into
runtime policy.

## Scope

- Add provider-neutral request-control types:
  - tool choice: auto, none, required, named;
  - optional named tool;
  - response format: text, JSON object, JSON schema;
  - optional JSON schema payload;
  - debug tags for provider-body capture.
- Extend capability reporting beyond `nativeTools`.
- Keep backward-compatible constructors or builders so existing tests remain
  readable.
- Update prompt-debug snapshots to include request-control metadata.
- Add tests with fake providers; do not implement llama.cpp in this ticket.

## Non-Goals

- No llama.cpp process management.
- No compat HTTP transport.
- No product setup/status rewrite.
- No cloud model integration.
- No removal of Ollama provider yet.

## Acceptance Criteria

- Tests prove `ChatRequest` can represent required tool choice, named tool
  choice, JSON object output, and JSON schema output.
- Tests prove existing callers that only pass messages/tools keep existing
  behavior.
- Tests prove capability reporting can distinguish native tools from required
  tool choice and schema output.
- Prompt-debug snapshots expose the request-control metadata without leaking
  secrets.
- Runtime code can inspect capabilities without depending on backend name.

## Suggested Verification

```powershell
./gradlew.bat test --tests "dev.talos.spi.*" --tests "dev.talos.core.llm.*PromptDebug*" --no-daemon
./gradlew.bat test --no-daemon
```

## Known Risks

- Adding fields directly to `ChatRequest` can create constructor churn. Prefer a
  compact options value or builder if it keeps call sites cleaner.
- Capability names must describe behavior, not provider brands.

## Known Follow-Ups

- T103 uses this spine to serialize compat chat requests.
- T104 uses this spine for llama.cpp capability reporting.
