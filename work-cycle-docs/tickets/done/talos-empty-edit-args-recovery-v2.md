# [done] Ticket: Empty Edit Args Recovery V2
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-empty-edit-args-functional-recovery.md`
- `work-cycle-docs/tickets/done/talos-mutation-prompt-empty-edit-args-recovery.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`

## Why This Ticket Exists

The completed empty-edit-args work made Talos safe: invalid `edit_file` calls
do not reach approval and do not mutate files.

Installed Talos verification against the broken BMI workspace showed the user
experience is still weak for real repair prompts.

Observed after an explicit apply request:

```text
[Used 6 tool(s): talos.edit_file, talos.read_file | 6 iteration(s)]
[4 failed] [failure policy stopped]

[Truth check: no file was changed in this turn because the requested write tool
call was invalid.]
```

Failures included repeated invalid `edit_file` calls for:

```text
public/script.js
script.js
index.html
```

with empty or missing `old_string`.

## Problem

The behavior is safe and truthful, but still not useful enough:

- the model can keep proposing empty edit args after reading files
- the loop may spend several iterations before stopping
- the final answer explains the safety failure but does not recover into a
  successful approval request

This is partly model behavior, but the runtime can make the failure path more
disciplined and measurable.

## Goal

Improve functional recovery or earlier controlled stop after repeated empty
`edit_file` arguments in explicit mutation turns.

## Scope

### In scope

- Detect repeated empty/missing `old_string` or `new_string` across paths in one
  mutation turn.
- After a same-file `read_file`, require the next `edit_file` for that file to
  include exact non-empty strings, or stop immediately.
- Consider a stronger reprompt that includes the exact required JSON shape but
  does not invent content.
- Keep final answer concise and truthful.

### Out of scope

- Letting invalid edits reach approval.
- Applying fallback writes without approval.
- Browser/shell validation.
- Large planner changes.

## Proposed Work

1. Extend loop state or failure policy to track repeated empty edit failures by
   path and by failure type.
2. Add a narrower stop before the general failure policy when the model repeats
   empty edit args after a read.
3. Add deterministic unit and JSON scenario coverage using the broken BMI shape.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Manual:

- Run installed Talos in `local/manual-testing/qa-workspaces/broken-bmi-stale`.
- Ask the explicit repair prompt.
- Confirm Talos either reaches a valid approval request or stops sooner with a
  clear no-change explanation.

## Acceptance Criteria

- Repeated empty edit args remain blocked before approval.
- No file changes occur without approval.
- The loop stops earlier or recovers more reliably than the current six-tool
  failure shape.
- The final answer remains truthful and concise.

## Completion Notes

Implemented on branch `ticket/talos-empty-edit-args-recovery-v2`.

- Treat missing `new_string` as part of the same invalid edit-argument family
  while preserving empty `new_string` as valid deletion when `old_string` is
  present.
- Added a cross-path empty/missing edit-argument stop after workspace files have
  been read.
- Kept valid recovery after read intact: a later exact `edit_file` can still
  reach approval.
- Added failure-policy reason text to invalid-mutation summaries so users can
  see why the loop stopped.
- Fixed ordering for recovered invalid edit failures: a failure is considered
  recovered only by a later same-path successful mutation, not by an earlier
  success.
- Added deterministic scenario
  `34-empty-edit-args-cross-path-stop.json`.

Verification:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallSupportTest" --tests "dev.talos.runtime.failure.FailurePolicyTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.emptyEditArgsAcrossPathsStop" --tests "dev.talos.harness.JsonScenarioPackTest.emptyEditArgsRecoverAfterRead" --tests "dev.talos.harness.JsonScenarioPackTest.mutationPromptEmptyEditArgsStopsCleanly"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
pwsh tools/uninstall-windows.ps1 -Quiet
./gradlew.bat --no-daemon installDist
pwsh tools/install-windows.ps1 -Force -Quiet
```

Installed Talos verification against
`local/manual-testing/qa-workspaces/broken-bmi-stale` stopped safely with no
approval request and no file changes. The live model did not reproduce the
cross-path failure shape in that run; deterministic unit and E2E coverage
exercise that shape directly.
