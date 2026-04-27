# [done] Ticket: Partial Edit Reread Repair Policy
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`
- `work-cycle-docs/tickets/done/talos-empty-edit-args-functional-recovery.md`

## Why This Ticket Exists

Manual installed-Talos QA against a deliberately broken BMI site showed a safe
but weak repair loop.

Prompt:

```text
This BMI website is not working correctly. Identify the problems first, then
apply the smallest edits needed to make it valid and functioning. Use file
tools, not just code blocks.
```

Observed behavior:

```text
[Used 7 tool(s): talos.grep, talos.list_dir, talos.read_file, talos.edit_file | 6 iteration(s)]
[3 failed] [failure policy stopped]

[Truth check: some requested file changes succeeded and some failed.]

Succeeded:
- index.html: Edited index.html: replaced 1 line(s) with 1 line(s)
Failed:
- index.html: old_string not found in index.html...
```

Talos made one valid edit, then repeatedly attempted stale or invalid
replacements until the failure policy stopped. The final answer was truthful,
but the site remained broken.

## Problem

After a successful edit to a file, later edit attempts against the same file may
use stale `old_string` text from before the mutation.

Talos currently has several useful pieces:

- `edit_file` failure messages tell the model to reread the file.
- `FailurePolicy` stops repeated failures safely.
- partial mutation summaries prevent false completion claims.

But there is no explicit reread/repair policy after:

```text
successful edit to file X -> failed edit to file X with old_string not found
```

This leaves the model to self-correct through generic reprompts. In the observed
case, it did not recover.

## Goal

When a turn has partial edit success and then stale edit failures on the same
file, Talos should either:

1. force a reread-before-next-edit recovery step, or
2. stop earlier with a concise incomplete repair summary that names the
   remaining failed target.

The behavior must stay bounded and must not weaken approval or edit validation.

## Scope

### In scope

- Detect stale edit failure after a same-file successful mutation.
- Add a targeted reprompt requiring `read_file` before any further `edit_file`
  on that path.
- Consider invalidating stale edit attempts on the same file until a reread has
  occurred.
- Improve partial mutation summary to include remaining repair uncertainty.
- Add deterministic tests for partial edit recovery.

### Out of scope

- Browser execution.
- Shell/test-runner validation.
- Applying edits without approval.
- Whole-file overwrite fallback for every failed edit.
- Broad planner implementation.

## Proposed Work

1. Track per-path mutation freshness in the tool loop.

   Candidate shape:

   ```text
   path index.html mutated at iteration N
   edit_file old_string not found for index.html at iteration N+1
   no read_file(index.html) after iteration N
   ```

2. Enforce a recovery step:

   ```text
   You edited index.html earlier in this turn. The file content has changed.
   Before another edit_file call for index.html, call read_file on index.html
   and use exact current text from that result.
   ```

3. Stop if the model ignores the reread requirement.

4. Keep final summaries honest:

   - if some edits succeeded and some failed, do not claim the repair is done
   - include enough remaining-failure detail for the user to continue

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Required cases:

- successful edit -> stale same-file edit failure -> forced reread -> valid
  next edit succeeds
- successful edit -> stale same-file edit failure -> model ignores reread ->
  controlled stop before hard iteration cap
- partial mutation final answer remains truthful
- approval behavior remains unchanged

Installed verification:

- Run the broken BMI repair prompt in a disposable workspace.
- Approve writes for the session.
- Confirm Talos either finishes the repair or clearly stops with a remaining
  incomplete status and no false completion claim.

## Acceptance Criteria

- Talos does not blindly repeat stale same-file edit attempts after a successful
  edit changed that file.
- Reread-before-retry behavior is deterministic and bounded.
- Existing failure-policy and approval-denial behavior stays intact.
- Manual broken-site repair is materially better or stops earlier with a
  clearer incomplete result.

## Completion Notes

- Added per-path tracking for files mutated since the last successful read.
- Detects `old_string not found` failures after a same-path mutation and emits a
  targeted stale-edit repair instruction.
- Blocks further `edit_file` calls for that path until a separate `read_file`
  turn result has been returned.
- Stops cleanly if the model ignores the reread requirement.
- Added unit tests for both stop and recovery paths, plus JSON scenario 29.
- Installed QA on a broken BMI workspace produced a truthful partial result; it
  exposed a separate partial-verification gap tracked in a follow-up ticket.
