# T222 - Proposal Apply Old-String Miss Uses Compact Target-Only Repair

Status: done
Severity: medium

## Problem

The T221 focused llama.cpp audit validated the T221 conditional review/fix budget fix, but found a Qwen-only README
proposal-apply failure:

`Apply that README.md proposal now.`

Qwen attempted `talos.edit_file -> README.md`, the runtime rejected the call because `old_string` was not found, then
Qwen read `README.md` successfully. At that point Talos had current target state, but the next generic tool-loop
continuation used enough history to exceed the local context budget:

`[Action obligation failed: retry could not fit in the context budget.]`

The output is safe, but the recovery path is weaker than it needs to be.

## Evidence

Audit:
`local/manual-testing/llama-cpp-t221-focused-repair-budget-audit-20260508-055658/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`

Relevant lines:
- prompt and target frame around lines `5573-5590`
- context-budget failure around lines `5606-5615`
- trace showing `edit_file` old-string miss followed by successful `read_file` around lines `5627-5645`

Code observation:
- `ToolCallExecutionStage` detects `old_string not found`.
- Stale-edit repair only records when the file mutated after a read.
- Static-web full-rewrite repair is special-cased for static web files.
- Generic proposal/Markdown old-string misses fall back to normal continuation, which can exceed context budget.

## Scope

- Detect an `edit_file` old-string miss for an expected target followed by a successful current-turn readback of that
  same target.
- Use one compact target-only repair attempt that includes:
  - the current user request,
  - the failed edit target and reason,
  - the latest readback content for that target,
  - the expected target path,
  - concise instructions to apply the requested proposal/change to the target.
- Narrow the repair tools to the mutation tools relevant to the target.
- Prefer a complete `talos.write_file` repair for small Markdown/prose proposal applications when possible.
- If compact repair emits a valid target mutation, execute it through the normal mutation/approval/checkpoint path.
- If compact repair emits no valid mutation, wrong target mutation, or cannot fit, stop with deterministic
  failure-dominant output.

## Acceptance

- Add a focused regression test for proposal apply where the first `edit_file` has an invalid `old_string`, a readback
  succeeds, and the compact target-only repair emits a valid `write_file`.
- The test must assert the final result has a successful mutation of `README.md`.
- The compact repair prompt must not include full conversation history.
- Add a failure test where the compact repair emits no valid mutation and the final output is failure-dominant.
- Existing exact-write context-budget fallback tests and static-web repair tests keep passing.

## Verification

- Red regression confirmed before implementation:
  - `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.oldStringMissWithReadbackUsesCompactTargetOnlyRepairBeforeContextBudgetFailure --no-daemon`
- Focused post-implementation tests:
  - `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.oldStringMissWithReadbackUsesCompactTargetOnlyRepairBeforeContextBudgetFailure --tests dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairNoToolProseBecomesDeterministicFailure --tests dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairRejectsReadOnlyToolBeforeExecution --no-daemon`
- Adjacent regression tests:
  - `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.staleSameFileEditCanRecoverAfterSeparateRead --tests dev.talos.runtime.ToolCallLoopTest.staleSameFileEditFailureRequiresRereadBeforeNextEdit --tests dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse --tests dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsRepeatedEditContinuationBeforeSuccessProse --tests dev.talos.runtime.ToolCallLoopTest.repairReadOnlyBudgetCountsSuppressedRedundantReadsBeforeAnotherContinuation --tests "*mutationRetryDoesNotFireAfterInvalidMutatingArgs" --tests "*exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt" --tests "*contextBudgetFallbackDoesNotRunForDeicticNonLiteralMutation" --no-daemon`
- Full verification:
  - `.\gradlew.bat test --no-daemon`
  - `.\gradlew.bat build installDist --no-daemon`
  - `git diff --check`

Notes:
- `git diff --check` exited clean with only existing CRLF conversion warnings.
- Focused README proposal-apply audit is still recommended after this commit because this ticket changes model-facing repair behavior.
