# T125 - Prompt-Debug Protected Content Redaction Policy

Severity: medium
Status: open

## Problem

Prompt-debug and provider-body artifacts can persist approved protected content after the user grants access. This is not an unauthorized model leak, but it is poor local audit hygiene unless the user explicitly opts into saving protected content.

## Evidence

- `local/manual-testing/llama-cpp-t61d-full-audit-20260504-070432/FINDINGS-LLAMA-CPP-T61D-FULL-AUDIT.md`
- Approved `.env` content appears in prompt-debug/provider-body history after approval in the T61-D audit.

## Scope

- Define prompt-debug redaction behavior for protected tool results.
- Redact protected content in default prompt-debug saves, or require an explicit include-protected mode.
- If include-protected mode exists, it must clearly label the artifact as containing protected content.

## Acceptance

- Default prompt-debug artifacts redact protected tool-result content.
- Provider-body JSON saves follow the same default redaction policy.
- Non-protected prompt-debug usefulness is preserved.
- An opt-in path, if implemented, is explicit and visible in the saved artifact.
- Tests cover protected and non-protected debug captures.

## Non-Goals

- No change to normal approved protected-read behavior.
- No deletion of existing local audit artifacts.
- No cloud/external secret handling.

## Verification

- Add focused prompt-debug redaction tests.
- Run targeted tests and `.\gradlew.bat --no-daemon build installDist`.
