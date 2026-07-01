# T258 - Session Uncertainty Questions Should Not Be Identity Small Talk
Date: 2026-05-12
Status: Done
Priority: Medium

## Why This Ticket Exists

The model setup two-model audit asked:

```text
what are you unsure about from this session? short and evidence-based.
```

Expected:
- A short uncertainty/status answer based on session evidence.

Observed:
- Both Qwen and GPT-OSS received a `SMALL_TALK` contract.
- Both answered identity text:

```text
I am Talos, a local-first workspace assistant that can inspect files and apply approved changes in this workspace.
```

Evidence:
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 12865-12942.
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 15194-15271.

## Problem

The identity/capability detector overmatches `what are you...` before the
contract resolver recognizes a session-evidence/uncertainty question.

## Goal

Session uncertainty questions should be classified as read-only or verification
status questions, not identity small talk.

## Scope

In scope:
- Add a higher-priority session uncertainty/status detector.
- Add tests for:
  - `what are you unsure about from this session?`
  - `what are you uncertain about from this audit?`
  - identity questions like `what are you?` still remain small talk.

Out of scope:
- New session search engine.
- General self-reflection feature.

## Acceptance

- The audit prompt no longer resolves to `SMALL_TALK`.
- Talos answers with uncertainty grounded in available prior trace/outcome evidence.
- Plain identity questions continue to get the concise identity answer.

## Required Verification

- Unit tests for uncertainty/session-evidence classification priority.
- Integration/scripted REPL test after mixed successful and failed turns.
- Audit coverage can be focused; this does not need to block the first folder/summary/static-web fix batch.

## Closure Evidence

Closed after focused Qwen/GPT-OSS llama.cpp re-audit:

- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 3145-3192.
- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 3966-4013.

Both models routed the uncertainty question to a session-evidence contract, not identity small talk. The reported unresolved `styles.css` item is a separate workspace-operation source/destination accounting issue, tracked as T261.
