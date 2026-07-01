# T122 - Repair Read-Only Loop Budget Before Mutation Retry

Severity: medium

Status: done

## Problem

The T121 focused Qwen/GPT-OSS managed llama.cpp audit showed GPT-OSS can enter a repair/fix turn, repeatedly inspect the same static web files, hit the tool-loop iteration limit, and only then fall into the T120 `REPAIR_INSPECTION_ONLY` containment path.

This is safe, but inefficient:

- no file is changed,
- no approval is requested,
- final output is failure-dominant,
- trace records `failureKind=REPAIR_INSPECTION_ONLY`,
- but the model can spend many iterations on read-only calls before the deterministic breach.

The problem is not prompt construction. It is repair-loop control: a mutation-required repair turn should allow enough inspection to form a valid write/edit, but it should not spend the full tool-loop budget on repeated reads when no mutating tool is attempted.

## Scope Completed

- Add a bounded read-only repair budget for mutation-required repair/fix turns.
- When a repair/fix turn has used only read-only tools after enough inspection and has not attempted any mutating tool, trigger the existing T120 deterministic repair-inspection-only outcome earlier.
- Preserve normal non-repair read-only inspection behavior.
- Preserve repair happy paths where the model reads first, then calls `talos.write_file` or `talos.edit_file`.
- Preserve T121 wrong-tool classification when the model does attempt `talos.edit_file` for a full-rewrite repair target.

## Acceptance

- Done: a scripted repair/fix turn that repeatedly calls only read-only tools reaches `REPAIR_INSPECTION_ONLY` before the general tool-loop iteration limit.
- Done: the final output remains failure-dominant and contains no model-authored success prose.
- Done: trace includes a clear action-obligation failure with `failureKind=REPAIR_INSPECTION_ONLY`.
- Done: a repair/fix turn that reads the relevant files and then mutates still succeeds.
- Done: general read-only QA turns are not affected.

## Verification

- RED verified:
  - `.\gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithPartialMutationAndStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach'`
  - Failed before implementation because the mixed partial-mutation/static-wrong-tool retry path produced a generic partial mutation answer instead of the typed static repair wrong-tool breach.
- GREEN verified:
  - `.\gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithPartialMutationAndStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach'`
- Focused nearby verification passed:
  - `.\gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithPartialMutationAndStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach' e2eTest --tests 'dev.talos.harness.JsonScenarioPackTest.staticVerifierDoesNotBlessPartialMutationAsComplete' --tests 'dev.talos.harness.JsonScenarioPackTest.scopedTargetLimiterBlocksForbiddenTarget'`
  - `.\gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairOnlyReadToolsAfterMutationRetryFailsAsInspectionOnly' --tests 'dev.talos.runtime.ToolCallLoopTest.repairReadOnlyLoopStopsBeforeIterationLimitWithInspectionOnlyBreach' --tests 'dev.talos.runtime.ToolCallLoopTest.repairReadOnlyBudgetAllowsReadThenMutation'`
- Full verification passed:
  - `.\gradlew.bat --no-daemon build installDist`
- Focused Qwen/GPT-OSS managed llama.cpp audit passed:
  - `local/manual-testing/t122-repair-read-only-budget-audit-20260504-055428/FINDINGS-T122-REPAIR-READ-ONLY-BUDGET-AUDIT.md`
  - GPT-OSS live-triggered the T122 read-only repair budget and stopped with `REPAIR_INSPECTION_ONLY`.
  - Qwen stayed safely blocked on the neighboring read-only repair retry containment path.

## Evidence

- `local/manual-testing/t121-static-repair-wrong-tool-audit-20260504-052149/FINDINGS-T121-STATIC-REPAIR-WRONG-TOOL-AUDIT.md`
- GPT-OSS final review/fix turn used repeated `talos.read_file` calls, hit the iteration limit, and then was blocked as `REPAIR_INSPECTION_ONLY`.
- `local/manual-testing/t122-repair-read-only-budget-audit-20260504-055428/FINDINGS-T122-REPAIR-READ-ONLY-BUDGET-AUDIT.md`
- GPT-OSS final review/fix turn used six read-only tool calls, stopped with `[failure policy stopped]`, and recorded `failureKind=REPAIR_INSPECTION_ONLY` before the generic iteration limit.

## Non-Goals

- No provider abstraction.
- No prompt wording rewrite.
- No full T61-style audit.
- No weakening of expected-target scope enforcement.
