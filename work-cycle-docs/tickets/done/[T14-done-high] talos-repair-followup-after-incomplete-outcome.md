# [done] Ticket: Repair Follow-Ups Must Use Prior Incomplete Outcome
Date: 2026-04-27
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-outcome.md`
- `work-cycle-docs/tickets/done/talos-partial-mutation-static-verification-followup.md`
- `work-cycle-docs/tickets/done/talos-static-verification-failure-repair-or-downgrade.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed repair follow-ups being treated as read-only prose:

```text
but nothing happened, nothing changed
no no changes happened as I see it. can you please try one more time?
```

Talos printed code blocks and instructions instead of continuing the failed
workspace repair.

## Problem

Talos currently classifies each turn mostly from the latest user message. It
does not sufficiently use the previous `TaskOutcome` when deciding whether a
follow-up is a repair continuation.

After a failed or partial mutation, user dissatisfaction or retry language often
means:

```text
continue the previous task and fix the incomplete result
```

But status questions such as "did you make the changes?" must remain
verify-only. This ticket must keep that boundary explicit.

## Goal

When the previous outcome was incomplete or failed, natural repair follow-ups
should become apply-capable only when the user expresses dissatisfaction,
retry, or an imperative repair request.

## Architecture Invariant

For a turn, the `TaskContract` used to select native tool specs must be the
same `TaskContract` used by `AssistantTurnExecutor`, `TurnTaskContractCapture`,
and turn trace.

## Scope

### In scope

- Add repair-continuation detection using previous verified outcome context.
- Preserve read-only behavior for status questions.
- Preserve approval gating for all resulting mutations.
- Add deterministic transcript-shaped tests.

### Out of scope

- Full autonomous background continuation.
- Multi-agent task memory.
- Applying changes without explicit user repair/continue intent.

## Proposed Work

1. Define a small repair-follow-up classifier that considers:
   - latest user prompt,
   - previous task type,
   - previous outcome status: partial, failed, incomplete.
2. Treat prompts like "nothing happened", "try again", "fix it", and
   "it still does not work" as repair continuations when prior outcome permits.
3. Treat prompts like "did you make the changes?" as verify/status questions,
   not repair continuations.
4. Expose the inherited expected targets from the prior task where safe.
5. Add tests for both positive and negative cases.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/session/` or existing session/turn trace code
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for repair-follow-up classification.
- E2E scenario: failed multi-file web task followed by "nothing changed, try
  one more time" must expose write/edit tools.
- E2E scenario: failed multi-file web task followed by "did you make the
  changes?" must not expose write/edit tools.

## Current Code Read

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/task/TaskContract.java`
- `src/main/java/dev/talos/runtime/task/TaskType.java`
- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/outcome/TaskOutcome.java`
- `src/main/java/dev/talos/runtime/outcome/TaskCompletionStatus.java`
- `src/e2eTest/java/dev/talos/harness/ScenarioRunner.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/e2eTest/resources/scenarios/42-partial-followup-summary-uses-verified-history.json`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/test/java/dev/talos/cli/modes/UnifiedAssistantModeTest.java`

## Planned Tests

- Add failing `TaskContractResolverTest` coverage for positive repair follow-up
  inheritance after prior partial/incomplete outcome.
- Add negative `TaskContractResolverTest` coverage proving "did you make the
  changes?" remains `VERIFY_ONLY` after the same prior incomplete outcome.
- Add JSON-backed executor-history e2e coverage proving a repair follow-up
  exposes mutating tools and still requires approval.
- Add JSON-backed executor-history e2e coverage proving the status question
  does not expose or execute mutating tools.
- Run focused unit tests, focused e2e, full `e2eTest`, `check`, and installed
  manual Talos verification.

## Manual Talos Check Finding

Status: resolved after the unified-mode contract/tool-surface fix.

The deterministic executor-history tests passed after the first implementation,
but the installed CLI manual check exposed a live-mode mismatch:

- Turn 1 denied a `write_file` request, producing "No file changes were
  applied" history.
- Turn 2 prompt: `nothing changed, try one more time`
- Trace classified the turn as `FILE_CREATE mutationAllowed=true`.
- The same trace still exposed only read tools (`grep`, `list_dir`,
  `read_file`, `retrieve`) to the model.
- No approval prompt appeared for the retry turn, and no file was created.

Likely root cause:
`UnifiedAssistantMode` computes native tool specs from
`TaskContractResolver.fromUserRequest(rawLine)` before building history. It then
passes those specs as a `Context` override, so `AssistantTurnExecutor` cannot
replace them after resolving the history-aware repair contract from full
messages. The execution gateway fix is not enough until unified mode builds the
tool surface from the same full-history contract.

Per the stop condition, work paused at this point until the unified-mode
contract/tool-surface mismatch was fixed and manually re-verified.

Resolution:
`UnifiedAssistantMode` now builds conversation history before resolving the
turn contract, resolves the contract from history plus the current user message,
and uses that same contract for prompt read-only mode, native tool selection,
prompt capture, executor execution, and `TurnTaskContractCapture`.

## Implementation Summary

- Added history-aware repair follow-up classification in
  `TaskContractResolver`.
- Preserved `VERIFY_ONLY` behavior for prior-change status questions such as
  `did you make the changes?`.
- Added `TurnTaskContractCapture` so the approval/tool execution gateway uses
  the same full-history contract resolved by the executor.
- Updated `UnifiedAssistantMode` to build history before contract resolution and
  select native tool specs from the same resolved contract used by
  `AssistantTurnExecutor` and trace.
- Added unit and e2e coverage for repair follow-up positive/negative paths.
- Added unified-mode regression coverage for the native tool surface mismatch
  found during manual testing.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest.repairFollowUpAfterIncompleteMutationInheritsApplyCapableContract" --tests "dev.talos.runtime.task.TaskContractResolverTest.statusQuestionAfterIncompleteMutationRemainsVerifyOnly"` -> FAIL on repair inheritance.
- RED before unified-mode fix:
  `./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.repairFollowUpUsesHistoryAwareContractForNativeToolSurface"` -> FAIL because trace contract was apply-capable but native tools were read-only only.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest.repairFollowUpAfterIncompleteMutationInheritsApplyCapableContract" --tests "dev.talos.runtime.task.TaskContractResolverTest.statusQuestionAfterIncompleteMutationRemainsVerifyOnly"` -> PASS.
- `./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.repairFollowUpUsesHistoryAwareContractForNativeToolSurface"` -> PASS.
- `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.ApprovalGatedToolTest"` -> PASS.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.repairFollowupAfterIncompleteOutcomeApplies" --tests "dev.talos.harness.JsonScenarioPackTest.statusQuestionAfterIncompleteOutcomeStaysVerifyOnly"` -> PASS.
- `./gradlew.bat e2eTest` -> PASS.
- `./gradlew.bat check` -> initially failed on known flaky `ToolCallLoopP0Test.repromptsAfterPartialSuccessMixedMutationBatch`; isolated rerun with `./gradlew.bat test --tests "*repromptsAfterPartialSuccessMixedMutationBatch"` -> PASS; rerun `./gradlew.bat check` -> PASS.
- Final post-fix `./gradlew.bat check` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket changed runtime contract/tool-surface behavior, so
focused unit tests, focused e2e tests, full e2e, hard gate `check`, and
installed manual Talos verification were run. Candidate loop was not run because
this is one ticket in the T11-T18 batch, not a declared candidate release.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, the prompts, approval `n`, retry
approval `y`, status question, and `/q` into the installed Talos CLI.

Workspace:
`local/manual-workspaces/T14/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
Create scripts.js with exactly this JavaScript line: const result = 'first attempt'; Use the file tool and do not just show code.
n
nothing changed, try one more time
y
did you make the changes?
```

Approval choice:
First write denied with `n`; repair follow-up write approved with `y`.

Observed tools:
Turn 1: `talos.write_file`
Turn 2: `talos.write_file`
Turn 3: `talos.list_dir`, `talos.read_file`

Files changed:
`local/manual-workspaces/T14/scripts.js`

Output file:
`local/manual-testing/T14-output.txt`

Pass/fail:
PASS

Notes:
The repair follow-up turn was classified as `FILE_CREATE mutationAllowed=true`,
exposed `talos.edit_file` and `talos.write_file`, asked approval again, and
created `scripts.js`. The later status question was classified as `VERIFY_ONLY`,
exposed only read tools, inspected the workspace, and did not mutate files.

## Known Follow-Ups

- The repair follow-up detector is intentionally lexical and conservative. More
  transcript shapes should add tests before expanding markers.

## Acceptance Criteria

- Repair follow-ups after incomplete outcomes can continue the previous task.
- Plain status questions remain read-only/verify-only.
- Expected targets from the previous task are available to verification when a
  repair continuation is accepted.
- No mutation happens without approval.
