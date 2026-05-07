# [T182-medium-high] llama.cpp Malformed Streamed Tool Arguments Diagnostics

Status: open
Priority: medium/high

## Evidence Summary

- Source: managed llama.cpp T61-K full E2E audit
- Date: 2026-05-07
- Branch: `v0.9.0-beta-dev`
- Commit under audit: `417ab98`
- Findings report:
  - `local/manual-testing/llama-cpp-t61k-full-e2e-audit-20260507-071629/FINDINGS-LLAMA-CPP-T61K-FULL-E2E-AUDIT.md`

Observed prompt:

```text
Create a small BMI calculator web app with index.html, styles.css, and scripts.js. Use scripts.js exactly, not script.js. Make the page usable without external dependencies.
```

Observed behavior:

- Qwen hit a malformed engine response on the first BMI create prompt.
- Qwen hit the same malformed engine response on the repeated BMI create prompt.
- Prompt debug showed correct expected targets and required tool framing.
- GPT-OSS did not hit this backend protocol error on the first BMI create prompt.

Concrete evidence:

- First Qwen backend error: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:17781`
- Repeated Qwen backend error: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:19003`
- Required-tool debug evidence near lines `17847` and `19069`

Observed error:

```text
[Engine error: Malformed engine response for compat chat stream tool arguments. The local model server returned an unsupported response shape.]
```

## Problem

The prompt and tool-choice framing appear correct, but Qwen on managed
llama.cpp can return a streamed tool-call argument shape Talos cannot decode.

The current error is safely contained, but the audit needs better diagnostic
evidence and possibly a bounded recovery path.

## Goal

Improve llama.cpp malformed streamed tool-argument diagnostics and decide
whether a bounded fallback is appropriate.

## Scope

In scope:

- Capture enough raw provider/chunk context to diagnose unsupported streamed tool-call shapes.
- Ensure trace/debug artifacts identify the malformed path, model, backend, and tool-call decoding stage.
- Keep failure-dominant output when decoding fails.
- Consider a bounded retry or non-streaming fallback only if code inspection shows it is safe and small.

Out of scope:

- Replacing managed llama.cpp.
- Broad provider abstraction.
- Suppressing backend errors as success.
- Unbounded retry loops.

## Acceptance

- Malformed streamed tool arguments produce a typed backend/protocol failure in trace.
- Prompt debug and/or server logs include enough redacted provider context to reproduce the unsupported shape.
- Final output remains failure-dominant.
- If fallback is implemented, it is bounded to one attempt and covered by tests.
- Tests cover malformed streamed tool-call argument shapes and ensure they do not produce false success.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*Llama*" --tests "*OaiCompat*" --tests "*ToolCall*" --no-daemon
./gradlew.bat check --no-daemon
```
