# T103 - Compat Chat Transport For Local Model Servers

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: 2026-05-03 engine backend pivot
Design: `docs/superpowers/specs/2026-05-03-talos-engine-neutral-llama-cpp-design.md`

## Evidence Summary

The next backend should not be hard-coded as a one-off llama.cpp serializer.
llama.cpp, vLLM, LocalAI, and other local servers expose similar
chat-completions-compatible HTTP APIs. Talos should implement one local compat
transport and let backend providers supply endpoint, capability, and option
differences.

Official references:

- llama.cpp server:
  `https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md`
- llama.cpp function calling:
  `https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md`
- vLLM tool calling:
  `https://docs.vllm.ai/en/latest/features/tool_calling/`
- LocalAI functions:
  `https://localai.io/features/openai-functions/`

## Classification

Primary taxonomy bucket: `TOOL_SURFACE`

Secondary buckets:

- `ACTION_OBLIGATION`
- `TRACE_REDACTION`
- `UNSUPPORTED_CAPABILITY`

Blocker level: release blocker for the llama.cpp backend

## Architectural Hypothesis

Talos should speak a generic local compatibility protocol for chat completions
instead of binding runtime code to one engine's request body. Providers should
map neutral `ChatRequest` controls into the server's supported JSON fields.

## Goal

Implement a reusable compat chat transport that can send messages, tools,
tool-choice controls, response-format controls, and parse text/tool-call
responses while capturing provider-body JSON for prompt debugging.

## Scope

- Add a transport for `POST /v1/chat/completions`.
- Support streaming and non-streaming responses.
- Serialize:
  - `model`;
  - `messages`;
  - `tools`;
  - `tool_choice`;
  - `response_format`;
  - schema payloads where supported.
- Parse:
  - text deltas;
  - assistant messages;
  - native tool calls;
  - malformed or unsupported response shapes as typed engine errors.
- Capture provider-body JSON when prompt debug is enabled.
- Add a fake HTTP server test fixture.

## Non-Goals

- No llama.cpp process launch in this ticket.
- No setup/status UX rewrite.
- No vLLM or LocalAI provider beyond transport-compatible test coverage.
- No cloud API keys.

## Acceptance Criteria

- Tests prove required tool choice serializes correctly.
- Tests prove named tool choice serializes correctly.
- Tests prove JSON object and JSON schema response formats serialize correctly.
- Tests prove streamed text and streamed tool calls produce correct
  `TokenChunk` values.
- Tests prove provider-body debug capture records the actual outbound JSON body.
- Tests prove unsupported response shapes fail clearly and do not become normal
  assistant prose.

## Suggested Verification

```powershell
./gradlew.bat test --tests "dev.talos.engine.compat.*" --tests "dev.talos.core.llm.*PromptDebug*" --no-daemon
./gradlew.bat test --no-daemon
```

## Known Risks

- Chat-completions-compatible servers vary in exact streaming chunk shape and
  tool-call support. Keep provider quirks explicit and tested.
- The user-facing wording should avoid implying OpenAI cloud usage.

## Known Follow-Ups

- T104 wraps this transport in a managed llama.cpp provider.
- T106 validates the transport with real llama.cpp server runs.
