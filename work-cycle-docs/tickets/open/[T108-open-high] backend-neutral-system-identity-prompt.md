# T108 - Backend-Neutral System Identity Prompt

Status: Open
Priority: High
Branch: v0.9.0-beta-dev
Source: T106 focused managed llama.cpp audit

## Evidence Summary

T106 provider-body JSON for the llama.cpp backend still included:

`You are privacy-first: you never exfiltrate data, and you only communicate with the local Ollama instance.`

Source: `src/main/resources/prompts/sections/identity.txt`.

## Goal

Remove Ollama-specific identity wording from the model-facing system prompt
unless the active backend is explicitly Ollama.

## Scope

- Replace static Ollama-specific identity text with backend-neutral local-engine
  wording.
- If dynamic backend naming is needed, inject it from active runtime config.
- Preserve privacy-first local-only semantics.
- Update prompt/debug tests so llama.cpp provider bodies do not mention Ollama.

## Acceptance Criteria

- llama.cpp provider-body prompt does not contain `Ollama`.
- Default identity prompt says Talos communicates with the configured local model
  engine or local backend.
- Ollama-specific wording appears only on explicit Ollama backend paths, if at
  all.
- Tests cover rendered prompt identity text for llama.cpp/default config.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*Prompt*" --tests "*LlmClient*" --no-daemon
```
