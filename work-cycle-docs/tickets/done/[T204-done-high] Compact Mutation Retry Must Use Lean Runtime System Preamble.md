# T204 - Compact Mutation Retry Must Use Lean Runtime System Preamble

Status: done
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

## Audit Result

Implemented in commit `a6e88ec`, but not accepted by focused audit.

Focused audit:

`local/manual-testing/llama-cpp-t204-focused-re-audit-20260507-203116/FINDINGS-LLAMA-CPP-T204-FOCUSED-RE-AUDIT.md`

Result:
- The full leading system prompt is no longer copied into the compact retry path.
- Both Qwen and GPT-OSS still fail the review/fix missing-mutation retry before backend dispatch because the retry request is still over the 8192-token local budget:
  - Qwen: estimated `5767`, budget `5635`.
  - GPT-OSS: estimated `5719`, budget `5635`.

Follow-up:
- T205 owns the remaining acceptance blocker: the missing-mutation retry must use a minimal retry envelope that fits the managed 8k local context path with real tool schemas.

Final closure:

T204's narrow implementation stayed in place and was carried forward into T205. T205 then replaced the still-too-large retry envelope with a minimal retry frame and compact retry tool schemas. The combined T204/T205 path is accepted by:

- deterministic compact retry tests,
- full unit/build verification,
- focused Qwen/GPT-OSS re-audit at `local/manual-testing/llama-cpp-t205-focused-re-audit-20260507-211437/FINDINGS-LLAMA-CPP-T205-FOCUSED-RE-AUDIT.md`.
