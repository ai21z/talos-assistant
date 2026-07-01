# [T178-done-high] Prompt-Debug Last Must Return User-Facing Turn

Status: done
Priority: high

## Evidence Summary

- Source: managed llama.cpp T61-K full E2E audit
- Date: 2026-05-07
- Branch: `v0.9.0-beta-dev`
- Commit under audit: `417ab98`
- Findings report:
  - `local/manual-testing/llama-cpp-t61k-full-e2e-audit-20260507-071629/FINDINGS-LLAMA-CPP-T61K-FULL-E2E-AUDIT.md`
- Raw transcripts:
  - `local/manual-testing/llama-cpp-t61k-full-e2e-audit-20260507-071629/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
  - `local/manual-testing/llama-cpp-t61k-full-e2e-audit-20260507-071629/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`

Observed behavior:

- `/prompt-debug last` sometimes captured the background conversation summarizer request.
- The captured prompt had `Messages: 0 total` and `Task contract: UNKNOWN`.
- The provider body contained the conversation summarizer system prompt, not the audited user-facing assistant turn.

Concrete evidence:

- Qwen:
  - line `23339`: `Messages: 0 total, 0 system, 0 user`
  - line `23341`: `Task contract: UNKNOWN`
  - line `23355`: provider body is the conversation summarizer prompt
  - repeated at lines `23746`, `24145`, `24555`, `24963`, `25368`, `25780`
- GPT-OSS:
  - line `22198`: `Messages: 0 total, 0 system, 0 user`
  - line `22200`: `Task contract: UNKNOWN`
  - line `22214`: provider body is the conversation summarizer prompt
  - repeated at lines `23566`, `24066`, `25549`

## Problem

Prompt-debug artifacts are part of Talos' audit evidence. If `/prompt-debug last`
can return a background summarizer request, the audit cannot reliably prove what
prompt reached the model for the last user-facing turn.

This is an audit-integrity bug, not a model-quality bug.

## Goal

`/prompt-debug last` must return the last user-facing/chat turn by default.
Background maintenance prompts must be identifiable separately and must not
overwrite the default "last audited turn" slot.

## Scope

In scope:

- Distinguish user-facing assistant calls from background maintenance calls in prompt-debug capture metadata.
- Keep a separate latest user-facing prompt-debug record.
- Make `/prompt-debug last` use the user-facing record.
- Preserve access to background prompt-debug captures through a clearly named path or metadata field.
- Ensure `/prompt-debug save` saves the audited user-facing turn by default.

Out of scope:

- Rewriting the summarizer.
- Changing model prompts.
- Changing task contract classification.

## Acceptance

- After a natural-language turn followed by background summarization, `/prompt-debug last` still returns the natural-language turn prompt.
- The returned debug artifact has the correct task contract and non-zero chat messages when the audited turn had messages.
- Background summarizer prompt-debug captures are still traceable, but do not overwrite the user-facing "last" pointer.
- Tests cover:
  - user-facing call followed by summarizer call,
  - `/prompt-debug last`,
  - `/prompt-debug save`,
  - metadata that identifies background maintenance calls.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*PromptDebug*" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```

## Resolution

Prompt-debug capture now keeps separate latest recorded and latest user-facing
snapshots. Background maintenance calls are tagged with
`prompt-debug:background-maintenance`, recorded for raw diagnostics, and excluded
from the default user-facing capture used by `/prompt-debug last` and
`/prompt-debug save`.

The conversation summarizer path now attaches the background-maintenance tag to
plain prompt-debug requests before they reach the engine adapter, so both the
pre-provider request and provider body capture are excluded from the audited
user-facing prompt slot.

## Verification

Passed:

```powershell
./gradlew.bat test --tests dev.talos.core.llm.LlmClientPromptDebugCaptureTest --no-daemon
./gradlew.bat test --tests dev.talos.cli.repl.slash.PromptDebugCommandTest --tests dev.talos.core.llm.LlmClientPromptDebugCaptureTest --no-daemon
./gradlew.bat check installDist --no-daemon
```
