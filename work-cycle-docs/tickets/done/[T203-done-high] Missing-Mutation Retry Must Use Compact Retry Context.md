# T203 - Missing-Mutation Retry Must Use Compact Retry Context

Status: done
Severity: high

## Problem

T202 narrowed the missing-mutation retry tool surface, but the focused re-audit still fails before the retry reaches the backend.

The remaining issue is that `AssistantTurnExecutor` builds the retry from the full current message list. Under managed local 8k context windows, the full history plus runtime summaries can still exceed the retry budget even when the retry exposes only mutation tools.

This blocks the product workflow before the model gets a useful bounded repair attempt.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t202-focused-re-audit-20260507-195617/FINDINGS-LLAMA-CPP-T202-FOCUSED-RE-AUDIT.md`

Qwen:
- Follow-up repair prompt starts at `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1218`.
- The model used read-only inspection, then the retry failed before backend dispatch:
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1240-1245`
  - estimated input tokens: `5713`
  - budget: `5635`

GPT-OSS:
- Follow-up review/fix prompt starts at `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1108`.
- The model inspected files but did not mutate, then the retry failed before backend dispatch:
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1130-1135`
  - estimated input tokens: `5671`
  - budget: `5635`

Both failures were safe and failure-dominant, but workflow completion is still not acceptable.

## Scope

- Build a compact retry message list for missing-mutation retries instead of sending the full prior conversation.
- Preserve the original conversation history outside the retry call.
- Include only the minimum context needed for the retry:
  - main system/runtime instructions required for tool use and policy,
  - current-turn capability frame,
  - latest current user request,
  - concise runtime-owned repair or expected-target context when present,
  - the missing-mutation retry instruction,
  - the prior mutation request only when the existing retry policy deliberately reissues it.
- Preserve T202's narrowed retry tool surface:
  - normal retry: `talos.write_file`, `talos.edit_file`
  - static full-rewrite repair retry: `talos.write_file`
- Preserve deterministic context-budget failure if the compact retry still cannot fit.
- Preserve failure-dominant final output with no success prose after retry failure.

## Non-Goals

- Do not change task classification.
- Do not broaden first-turn tool surfaces.
- Do not add provider abstraction or llama.cpp server changes.
- Do not change static verifier semantics.
- Do not hide useful runtime-owned diagnostics from the final failure output.

## Acceptance

- Tests prove the retry request excludes older irrelevant history and prompt-debug payloads.
- Tests prove the retry request preserves the latest user request and current-turn capability frame.
- Tests prove static full-rewrite repair context survives compaction.
- Tests prove a compact retry can proceed in a scripted context-budget scenario where the old full-history retry would fail.
- Tests prove successful retry tool calls still execute through the normal tool loop.
- Existing T197/T201/T202 tests pass.
- Full `test` and `build installDist` pass.
- Run the focused Qwen/GPT-OSS audit shape again after implementation.

## Implementation

- Added compact missing-mutation retry messages in `AssistantTurnExecutor`.
- The retry backend call now includes only leading durable system instructions, the latest static repair context when present, the current-turn capability frame, the runtime-owned no-action summary, and the retry instruction.
- The original session message list still records the runtime-owned retry summary/frame/instruction; the backend retry no longer sends the full old history.
- The retry keeps T202's narrowed mutation tool surface.

## Verification

- `./gradlew.bat test --tests dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest --no-daemon`
- `./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon`
- `./gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest --no-daemon`
- `./gradlew.bat test --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest --no-daemon`
- `./gradlew.bat test --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest --no-daemon`
- `./gradlew.bat test --tests dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest --no-daemon`
- `./gradlew.bat test --tests dev.talos.core.llm.LlmClientContextBudgetTest --no-daemon`
- `./gradlew.bat test --no-daemon`
- `./gradlew.bat build installDist --no-daemon`
