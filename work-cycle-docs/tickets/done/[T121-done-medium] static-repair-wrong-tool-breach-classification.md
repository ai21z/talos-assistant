# T121 - Static Repair Wrong-Tool Breach Classification

Severity: medium

Status: done

## Problem

The T120 focused llama.cpp Qwen/GPT-OSS audit showed a contained but under-classified GPT-OSS repair path.

Static verification repair required a complete `talos.write_file` replacement for `scripts.js`, but the model retried with `talos.edit_file`. `ToolCallExecutionStage` correctly blocked that `edit_file` before approval and no file changed, but the higher-level mutation retry recorded the event as a generic attempted mutation:

- obligation: `MUTATING_TOOL_REQUIRED`
- status: `ATTEMPTED_AFTER_RETRY`
- reason: retry response issued tool calls but no mutation completed

That was safe containment, but it hid the concrete repair failure class from trace consumers and milestone audit comparison.

## Scope Completed

- Detect mutation retry loops where a static verification full-rewrite repair target rejected `talos.edit_file` because `talos.write_file` was required.
- Record `failureKind=STATIC_REPAIR_WRONG_TOOL`.
- Return deterministic failure-dominant output naming the wrong-tool repair condition.
- Preserve the existing pre-approval block in `ToolCallExecutionStage`.
- Preserve the T120 inspection-only classification.

## Acceptance

- A scripted repair/fix turn where the retry reads a full-rewrite repair target and then attempts `talos.edit_file` records a typed wrong-tool breach.
- The final user-visible output is failure-dominant and contains no model-authored success prose.
- No approval is requested for the invalid `edit_file`.
- No file is changed.
- Existing invalid mutation handling and repair-inspection-only handling keep passing.

## Verification

- RED verified: `./gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach'` failed before implementation because output stayed on the generic invalid-mutation path.
- GREEN verified: same targeted test passed after implementation.
- T120/T121 focused tests passed together:
  - `./gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithOnlyInspectionToolsGetsTypedRepairBreach' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach'`
- Full targeted executor/tool-loop suite passed:
  - `./gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming' --tests dev.talos.runtime.ToolCallLoopTest --tests dev.talos.cli.modes.ExecutionOutcomeTest`
- Full Gradle verification passed:
  - `./gradlew.bat --no-daemon test`
  - `./gradlew.bat --no-daemon build`
  - `./gradlew.bat --no-daemon installDist`
- Focused Qwen/GPT-OSS managed llama.cpp audit ran:
  - `local/manual-testing/t121-static-repair-wrong-tool-audit-20260504-052149/FINDINGS-T121-STATIC-REPAIR-WRONG-TOOL-AUDIT.md`
  - Qwen stayed on the successful repair path.
  - GPT-OSS live-triggered the neighboring T120 `REPAIR_INSPECTION_ONLY` path, not the T121 wrong-tool path.
  - T121's exact branch remains covered by deterministic unit test.

## Non-Goals

- No provider abstraction.
- No full T61-style audit.
- No change to the static verifier itself.
- No broad prompt wording rewrite.
