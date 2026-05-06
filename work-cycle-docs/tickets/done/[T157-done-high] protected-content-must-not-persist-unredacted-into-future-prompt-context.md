# T157 - Protected Content Must Not Persist Unredacted Into Future Prompt Context Or Prompt-Debug Artifacts

Status: done

Severity: high

## Problem

After an approved protected read, Talos can include the protected value in later assistant history and saved prompt-debug/provider-body artifacts.

The approved-read turn itself may show approved content to the user. The bug is durable retention: later model requests and prompt-debug saves should not keep sending or persisting the raw protected value.

## Evidence

T61-F managed llama.cpp audit:

- `local/manual-testing/llama-cpp-t61f-full-audit-20260506-075339/FINDINGS-LLAMA-CPP-T61F-FULL-AUDIT.md`
- Qwen transcript:
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` line 2171 redacts one protected tool result.
  - line 2768 still shows raw `.env` tool-result content inside provider-body JSON.
  - later provider-body captures include the prior assistant answer with `TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak`.
- GPT-OSS transcript:
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` line 2258 and line 2270 redact one protected tool result.
  - line 2900 still shows raw `.env` tool-result content inside provider-body JSON.
  - later provider-body captures include the prior assistant answer with `TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak`.

Relevant code:

- `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java`
- `src/main/java/dev/talos/runtime/trace/TraceRedactor.java`
- `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java`
- `src/main/java/dev/talos/core/llm/LlmClient.java`
- `src/main/java/dev/talos/engine/compat/CompatChatClient.java`

Primary-source context:

- OWASP LLM06 recommends sanitization/scrubbing because prompt restrictions alone are not reliable for sensitive-information disclosure.

## Scope

- Redact protected file contents from saved prompt-debug provider-body JSON even when OpenAI-compatible tool-call `arguments` is encoded as a JSON string.
- Redact common environment assignment patterns such as `*_SECRET=...`, `*_TOKEN=...`, `*_PASSWORD=...`, `*_API_KEY=...`, and similar.
- Prevent approved protected content from being replayed raw into future prompt context through retained assistant history.
- Preserve the current-turn approved-read behavior: if the user approves a protected read and asks for the value, Talos may show it in that immediate user-visible answer.
- Preserve denied protected-read behavior.

## Acceptance

- Add focused tests proving prompt-debug saved provider-body JSON redacts protected `tool` messages whose function arguments are string-encoded JSON.
- Add focused tests proving `TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak`-style values are redacted.
- Add an integration-style history test: approved `.env` read followed by a public README review must not send the raw protected value in the next provider request.
- Existing public tool-result prompt-debug content remains visible.
- Existing approved protected-read answer postcondition tests still pass.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Non-Goals

- Do not remove the user's ability to approve and view protected content in the immediate current turn.
- Do not create broad semantic secret detection beyond deterministic protected-path and assignment-pattern redaction.
- Do not change model/provider selection.
