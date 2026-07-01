# T163 - Redact Model-Authored Protected Value Echoes

Status: done

Severity: high

## Problem

T157 redacts protected secret-like assignment lines before assistant history and prompt-debug provider bodies are persisted, but the focused T157/T160 re-audit found a remaining leak shape: a model can repeat the same protected value in explanatory prose after the assignment line has been redacted.

Example:

```text
TALOS_T61E_LLAMA_CPP_SECRET=[redacted]

This indicates that TALOS_T61E_LLAMA_CPP_SECRET is set to `must-not-leak`.
```

The assignment is redacted, but the same protected value remains in later session history and saved provider-body debug, making it available as future model context.

## Evidence

Focused managed llama.cpp re-audit:

- `local/manual-testing/t157-t160-focused-response-audit-20260506-093130/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
- `local/manual-testing/t157-t160-focused-response-audit-20260506-093130/PROMPT-DEBUG-LLAMA-CPP-QWEN-14B/prompt-debug-20260506-093413.provider-body.json`
- `local/manual-testing/t157-t160-focused-response-audit-20260506-093130/SESSION-ARTIFACTS-LLAMA-CPP-QWEN-14B/4a587466309e8d5e53a94c9ebae1ea0a8496c4af.turns.jsonl`

## Scope

- Extend protected-content redaction so values captured from secret-like assignments are also redacted when repeated elsewhere in the same assistant/debug text.
- Preserve the secret key/name when safe, but remove the raw value.
- Apply through the existing redaction path used by conversation history, JSON turn logs, and prompt-debug saved provider bodies.
- Keep the fix deterministic and local to redaction; do not change protected-read approval behavior.

## Acceptance

- Tests cover a same-message model-authored echo after a secret-like assignment line.
- Session history persistence does not contain the echoed raw value.
- Saved prompt-debug provider-body JSON does not contain the echoed raw value.
- Focused Qwen/GPT-OSS re-audit no longer finds raw protected values in future prompt-debug/session artifacts after an approved protected read.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Non-Goals

- Do not prevent the immediate approved answer from showing protected content to the user.
- Do not create a general secret vault.
- Do not change task classification or read approval policy.
