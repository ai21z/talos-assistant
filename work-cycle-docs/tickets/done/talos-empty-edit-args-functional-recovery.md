# [done] Ticket: Empty Edit Args Functional Recovery
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-mutation-prompt-empty-edit-args-recovery.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`
Related tickets:
- `work-cycle-docs/tickets/done/talos-pre-approval-edit-arg-validation.md`
- `work-cycle-docs/tickets/done/talos-invalid-mutation-should-not-trigger-missing-mutation-retry.md`

## Why This Ticket Exists

The completed empty-edit-args ticket made Talos safe: invalid empty
`edit_file` arguments are blocked before approval, repeated failures stop
cleanly, and the final answer says no file changed.

Installed verification showed the remaining product problem: a simple requested
edit can still fail to complete because the model repeats empty edit arguments
after reading the file.

Safety is correct. Functional recovery is still weak.

## Problem

Observed prompt:

```text
Now apply the smallest fix by editing index.html so the CSS and JavaScript .cta-button selector has a matching element in the HTML. Use the file edit tool; do not just show code.
```

Observed behavior:

- model called `edit_file` with empty `old_string` / `new_string`
- Talos blocked the invalid call before approval
- model read `index.html`
- model repeated empty edit args
- failure policy stopped cleanly
- no file changed

The final answer was truthful, but the requested edit was not applied.

## Goal

Improve recovery after an empty `edit_file` call so straightforward edits have
a better chance of reaching a valid approval request, without allowing invalid
mutations to reach approval.

## Scope

### In scope

- Improve reprompt instructions after empty edit args.
- After a read succeeds, require the next mutation attempt to contain non-empty
  `old_string` and `new_string`, or stop with a clearer non-recoverable
  diagnostic.
- Consider suggesting/switching to `write_file` only when the model can supply
  complete valid file content and the user requested a whole-file replacement
  or generation.
- Add deterministic e2e coverage for recovery and controlled-stop shapes.

### Out of scope

- Letting empty edit args reach approval.
- Applying any edit without approval.
- Blindly generating whole-file overwrites as a fallback for every failed edit.
- Browser/shell validation.

## Proposed Work

1. Improve tool-result feedback.

   Current feedback is safe but may not be directive enough. It should tell the
   model exactly:

   ```text
   You have now read index.html. The next edit_file call must include exact
   old_string copied from the file content and non-empty new_string. If you
   cannot form that, stop and explain no edit was applied.
   ```

2. Add a one-step repair lane.

   If the pattern is:

   ```text
   empty edit -> read same file -> empty edit
   ```

   then either:

   - stop immediately with a concise explanation, or
   - issue one specialized repair prompt before failure policy stops

   The choice should be driven by deterministic tests and loop-safety.

3. Keep failure discipline central.

   Do not add scattered answer patches. Prefer `FailurePolicy`,
   `ToolCallExecutionStage`, or `ToolCallRepromptStage`.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Required cases:

- empty edit -> read -> valid edit reaches approval
- empty edit -> read -> repeated empty edit stops with no approval and no file
  change
- failure summary is concise and ASCII-safe
- existing invalid-mutation/no-approval behavior remains unchanged

Installed verification:

- Re-run the `.cta-button` fix prompt in `local/playground/horror-synth-site`.
- Deny approval when requested unless using a disposable copy.
- Verify no repeated empty-arg loop and no file mutation without approval.

## Acceptance Criteria

- Repeated empty edit args remain safe.
- Talos either recovers to a valid approval request or stops earlier with a
  clear no-change explanation.
- The behavior is covered by deterministic tests.
