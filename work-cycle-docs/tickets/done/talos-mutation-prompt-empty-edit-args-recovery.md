# [done] Ticket: Mutation Prompt Empty Edit Args Recovery
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-pre-approval-edit-arg-validation.md`
- `work-cycle-docs/tickets/done/talos-invalid-mutation-should-not-trigger-missing-mutation-retry.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`

## Why This Ticket Exists

Installed Talos manual verification showed that a clear mutation request can
still produce an initial `talos.edit_file` call with empty `old_string` and
`new_string`. The pre-approval validator blocks the call before approval and no
file changes, but the turn may fail before reaching a user approval prompt.

## Problem

The current safety behavior is correct:

- invalid edit arguments are blocked before approval
- no file changes
- failure policy stops repeated invalid calls
- final answer says the mutation did not happen

But the user experience is still weak for a normal apply request. The model may
read the file after the first invalid attempt, then repeat empty edit arguments
until failure policy stops the loop. This means a straightforward edit request
can end as a safe failure instead of either producing a valid approval request or
stopping earlier with a cleaner repair instruction.

## Goal

Improve recovery from empty `edit_file` arguments during explicit mutation
turns without weakening pre-approval validation or reintroducing blind mutation
retries.

## Scope

### In scope

- Analyze the current invalid-edit feedback path from:
  - `TurnProcessor.validateBeforeApproval(...)`
  - `ToolCallExecutionStage`
  - `ToolCallRepromptStage`
  - `FailurePolicy`
- Consider whether repeated empty edit args should trigger a specialized stop
  after fewer attempts.
- Consider whether the invalid edit feedback should include more concrete
  "copy exact old_string from the read_file result" instructions.
- Add deterministic unit/e2e coverage for repeated empty edit args on a mutation
  turn.

### Out of scope

- Allowing invalid edit calls to reach approval.
- Applying edits without explicit approval.
- Re-enabling broad missing-mutation retries after invalid mutations.
- Adding shell/browser/test-runner tools.

## Proposed Work

- Add a narrow repeated-empty-edit detector, preferably in failure-policy or
  tool-call reprompt logic rather than answer-string patching.
- If the model repeats an empty `edit_file` after reading the file, stop with a
  direct failure summary that says no approval was requested and no file changed.
- Preserve existing behavior for other invalid mutation shapes unless tests show
  the same failure pattern.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/FailurePolicy.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused unit test for an explicit mutation prompt where scripted model output:
  1. emits empty `edit_file`
  2. reads the target
  3. repeats empty `edit_file`
- Verify no approval is requested and no file changes.
- Verify the loop stops cleanly with a truthful no-change summary.
- Run full `test`, `e2eTest`, `check`, and installed Talos manual verification
  when implemented.

## Acceptance Criteria

- Repeated empty edit arguments do not loop until the general failure cap when a
  narrower stop is available.
- Talos remains safe: no approval prompt for invalid args and no mutation.
- The final answer clearly says no file was changed and why.
- Existing invalid-mutation and approval-denial behavior does not regress.
