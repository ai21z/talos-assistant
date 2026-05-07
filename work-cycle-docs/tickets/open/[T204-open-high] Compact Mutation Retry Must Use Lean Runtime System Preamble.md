# T204 - Compact Mutation Retry Must Use Lean Runtime System Preamble

Status: in-progress
Severity: high

## Problem

T203 compacts missing-mutation retry messages by removing old irrelevant history, but the focused audit still fails for GPT-OSS.

The remaining cause is that the compact retry still preserves the full leading system prompt. In real Talos runs, that prompt is large: it includes workspace overview, behavior rules, tool-policy prose, and other ordinary turn instructions. A bounded retry does not need that whole preamble because the retry already has native tool schemas, a current-turn capability frame, and a runtime-owned retry instruction.

Under the managed 8k local context window, carrying the full leading prompt can still push the retry over budget.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t203-focused-re-audit-20260507-201602/FINDINGS-LLAMA-CPP-T203-FOCUSED-RE-AUDIT.md`

GPT-OSS:
- Review/fix prompt starts at `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1164`.
- Missing-mutation retry still fails before backend dispatch:
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1188`
  - estimated input tokens: `5689`
  - budget: `5635`

Qwen:
- Same review/fix prompt starts at `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1198`.
- Qwen avoids the retry by producing a conditional no-change result:
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1222`

## Scope

- Change compact missing-mutation retry messages to use a short runtime-owned retry system preamble instead of copying the full leading system prompt.
- Keep this change limited to the compact retry path.
- Preserve:
  - current-turn capability frame,
  - retry instruction,
  - static verification repair context when present,
  - T202 narrowed retry tool surface,
  - deterministic context-budget failure if the lean retry still cannot fit,
  - failure-dominant final output.
- The lean preamble must still state:
  - Talos is a local workspace assistant,
  - use only the provided Talos tools,
  - the runtime handles tool invocation, approval, permissions, and verification,
  - do not claim changes unless a write/edit tool succeeds,
  - follow the current-turn capability frame.

## Non-Goals

- Do not change ordinary first-turn prompts.
- Do not remove the full system prompt from normal conversation turns.
- Do not change task classification.
- Do not change the static verifier.
- Do not add provider abstractions or llama.cpp server changes.

## Acceptance

- Tests prove compact retry requests exclude a large leading system prompt.
- Tests prove compact retry requests include the lean runtime retry preamble.
- Tests prove current-turn frame, latest user request, and static repair context still survive.
- Tests prove a scripted retry with a large real-like leading system prompt can reach the backend under a tight request-size guard.
- Existing T201/T202/T203 targeted tests pass.
- Full `test` and `build installDist` pass.
- Re-run the same focused Qwen/GPT-OSS audit shape.
