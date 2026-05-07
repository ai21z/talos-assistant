# T202 - Missing-Mutation Retry Must Use Minimal Mutation Tool Surface

Status: done
Severity: high

## Problem

T201 narrowed the ToolCallRepromptStage pending-obligation path and fixed the GPT-OSS initial BMI create failure. The focused T201 re-audit then exposed the adjacent retry path: AssistantTurnExecutor's missing-mutation retry still sends the broad mutation tool surface.

This matters under managed local 8k context windows. The broad retry prompt can exceed budget by a small margin before the model gets a useful chance to act.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t201-focused-re-audit-20260507-193919/FINDINGS-LLAMA-CPP-T201-FOCUSED-RE-AUDIT.md`

GPT-OSS:
- Initial create now passes after T201: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:567-572`
- The next review/fix turn still fails before retry:
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1042-1071`
  - estimated input tokens: `5646`
  - budget: `5635`

Qwen:
- Create writes all expected targets but static verification fails correctly:
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:684-702`
- The follow-up repair turn hits the same broad retry budget failure:
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1330-1359`
  - estimated input tokens: `5701`
  - budget: `5635`

## Scope

- Apply T201's narrow-tool-surface principle to AssistantTurnExecutor's missing-mutation retry.
- For normal missing-mutation retries, send only:
  - `talos.write_file`
  - `talos.edit_file`
- For static full-rewrite repair retries, send only:
  - `talos.write_file`
- Preserve first-turn broad mutation tool surfaces.
- Preserve deterministic context-budget failure if the narrowed retry still cannot fit.
- Do not change task classification in this ticket.

## Acceptance

- Add focused tests that assert the actual retry `ChatRequest` tool list is narrowed.
- Add/keep coverage that context-budget failure remains deterministic if the narrowed retry still exceeds budget.
- Existing T197/T201 pending-obligation and context-budget tests still pass.
- Run targeted tests, full `test`, and `build installDist`.
- Re-run the focused Qwen/GPT-OSS audit shape after implementation.
