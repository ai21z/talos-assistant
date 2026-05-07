# T208 - Static Repair Continuation Retry Must Fit 8k Budget

Status: done
Severity: high

## Problem

The T61-Q managed llama.cpp full audit showed GPT-OSS could hit a local context-budget failure during a static repair/fix continuation.

Talos failed safely and did not emit success prose, but the product workflow was still degraded: after a failed static verification and a user repair/fix request, Talos could inspect a file, attempt to continue into a bounded repair, then stop because the continuation retry payload was too large for the local 8k budget.

## Evidence

Audit:

`local/manual-testing/llama-cpp-t61q-full-e2e-audit-20260507-215146/`

Key lines:

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:18042`
  - `[Action obligation failed: retry could not fit in the context budget.]`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:18044`
  - estimated 5815 input tokens, budget 5635, context window 8192.
- `SESSION-ARTIFACTS-LLAMA-CPP-GPT-OSS-20B/4021f4ce28c82afbc4d4216b99818fafe2e3f7f4.turns.jsonl:27`
  - turn 27: `Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.`
  - tool calls before failure: `talos.list_dir`, `talos.read_file(index.html)`.
- `PROMPT-DEBUG-LLAMA-CPP-GPT-OSS-20B/prompt-debug-20260507-220526.md`
  - `[Static repair progress]` named `index.html, scripts.js, styles.css`.

## Scope

- Keep failure-dominant output as-is when a retry still cannot fit.
- Make static repair/progress continuations use a smaller retry payload before calling the backend.
- Prefer complete-file repair targets with the minimum necessary tool surface, ideally only `talos.write_file`.
- Preserve prompt-debug visibility for the compact retry.
- Preserve the existing happy path when the model emits the required write calls.

## Acceptance

- Add a focused test where static repair continuation with broad prior context would exceed budget unless the retry path is compacted.
- Assert the retry request sent to the backend uses a compact repair payload and does not include irrelevant old conversation turns.
- Assert static full-rewrite repair continuation exposes only the required mutation tool surface.
- Assert context-budget failure remains deterministic and failure-dominant if the compact retry still cannot fit.
- Existing Qwen-style conditional no-change path remains unchanged.

## Resolution Notes

Implemented in `ToolCallRepromptStage` and `AssistantTurnExecutor`.

Static repair progress now becomes an active pending obligation after read-only inspection when static repair context exists, even if no mutation happened in that iteration. The backend retry request for that path is compacted to:

- a short static-repair system instruction,
- the last `[Static verification repair context]`,
- the `[Static repair progress]` instruction,
- the current user task.

The canonical loop transcript remains intact, but the backend retry payload no longer carries broad prior conversation history or the broad read-only tool manual. Static full-rewrite repair retries expose only `talos.write_file`.

`AssistantTurnExecutor` now also recognizes the pre-execution static-repair wrong-tool breach form, so the user-facing failure remains specific when the pending obligation gate rejects an invalid attempted repair target or tool before execution.

## Tests

Passed:

- `.\gradlew.bat test --tests dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest --tests dev.talos.runtime.ToolCallLoopTest --tests dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming' --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`

Full unit test result: 3375 tests completed, 2 skipped.

## Focused Re-Audit

Audit:

`local/manual-testing/llama-cpp-t208-focused-re-audit-20260507-223211/FINDINGS-LLAMA-CPP-T208-FOCUSED-RE-AUDIT.md`

Result:

- Qwen passed the static BMI create path directly with 3 `talos.write_file` calls and static verification passed.
- GPT-OSS reproduced the wrong-target static create shape, then exercised the repair-continuation path.
- The repair-continuation retry used `tool_choice: REQUIRED`, 4 compact messages, and only `talos.write_file`.
- The previous context-budget failure did not reproduce.
- GPT-OSS attempted `talos.write_file(README.md)` instead of remaining repair target `scripts.js`; Talos rejected this as a deterministic pending static repair obligation breach with no success prose.

Decision:

T208 is closed. The remaining GPT-OSS wrong-target behavior is contained by the runtime and is separate from the T208 context-budget failure.
