# [done] Ticket: Invalid Mutation Failure Should Not Trigger Missing-Mutation Retry

Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`
- `work-cycle-docs/tickets/done/talos-pre-approval-edit-arg-validation.md`

## Why This Ticket Exists

Installed CLI verification after the selector grounding fix showed this turn
shape:

1. User explicitly asked Talos to edit `index.html`.
2. The model emitted `talos.edit_file` with empty `old_string` and
   `new_string`.
3. Pre-approval validation correctly rejected the call without asking approval.
4. Failure policy stopped the repeated invalid edit loop after repeated
   `index.html` failures.
5. `AssistantTurnExecutor.mutationRequestRetryIfNeeded(...)` then fired because
   the user asked for a mutation and zero mutating tools succeeded.
6. The retry restarted another invalid `edit_file` loop.

No workspace file changed, but the runtime wasted a second tool loop after the
failure policy had already made the correct stop decision.

## Problem

Missing-mutation retry treats all "explicit mutation request + zero mutation
successes" turns the same. It does not distinguish:

- no mutating tool was attempted
- a mutating tool was denied by approval
- a mutating tool failed validation
- failure policy already stopped repeated invalid attempts

The denial case is already special-cased. Invalid mutation/failure-policy stop
needs the same discipline.

## Goal

Do not trigger missing-mutation retry after invalid mutating tool failures or
after the failure policy has stopped the loop.

The final answer should summarize the invalid mutation outcome once and avoid
starting a second invalid retry loop.

## Scope

### In scope

- Gate `mutationRequestRetryIfNeeded(...)` on failure-policy stop.
- Gate it on invalid mutating outcomes such as `ToolError.INVALID_PARAMS`.
- Add deterministic regression tests.
- Stop tool-loop continuation after mutating DENIED outcomes that are not
  approval prompts, while preserving one response-only synthesis from already
  gathered evidence.
- Keep mixed invalid-plus-denied mutation summaries truthful: approval denial
  dominates the no-success completion state, while earlier invalid attempts
  remain visible.

### Out of scope

- Broad planner changes.
- Changing approval-denial behavior.
- Changing edit-file validation semantics.

## Proposed Work

- Extend `AssistantTurnExecutor` with a predicate for invalid mutating failures,
  ideally using `ToolOutcome.errorCode()`.
- In `mutationRequestRetryIfNeeded(...)`, return without retry when:
  - `hasDeniedMutation(loopResult)` is true
  - failure policy has stopped the loop
  - invalid mutating failure is present
- Ensure the existing invalid mutation outcome summary remains the final
  centralized truth layer.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- possibly `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit: explicit mutation request + invalid mutating outcome does not call the
  retry LLM.
- Unit: failure-policy-stopped loop does not trigger missing-mutation retry.
- Installed Talos manual run should show one invalid mutation summary, not a
  second retry loop.

## Acceptance Criteria

- invalid edit args do not ask approval
- invalid edit args do not trigger missing-mutation retry
- failure-policy stop is respected by the executor-level retry gate
- no workspace files change

## Completion Notes

Implemented on `fix/talos-invalid-mutation-no-missing-retry`.

What changed:

- `AssistantTurnExecutor.mutationRequestRetryIfNeeded(...)` now skips retry
  after failure-policy stop and invalid mutating failures.
- `ToolCallExecutionStage` reports mutating DENIED outcomes distinctly enough
  for `ToolCallRepromptStage` to stop further tool execution.
- `ToolCallRepromptStage` allows one response-only synthesis after a
  non-approval mutating denial, then terminates the loop. If the model tries
  another tool during that synthesis, Talos uses a bounded stop message instead
  of executing another tool.
- `ExecutionOutcome`/`MutationOutcome` now treat a no-success turn containing
  approval denial plus earlier invalid attempts as blocked by denial, while
  still listing the invalid attempts.

Verification:

- `./gradlew.bat --no-daemon test`
- `./gradlew.bat --no-daemon e2eTest`
- `./gradlew.bat --no-daemon check`
- Installed Talos uninstall/build/install/manual horror-synth run.

Manual result:

- Read-only selector inspection stayed non-mutating on disk and stopped before
  the iteration cap even when the model attempted unsolicited edits.
- Explicit edit turn requested approval for a valid `index.html` edit, `n`
  denied it, the loop stopped immediately, and the final answer reported no
  file change because approval was denied.
- `local/playground/horror-synth-site` had no diff after the run.
