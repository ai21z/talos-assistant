# [done] Ticket: Recognize Repair As Explicit Mutation Intent
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-task-contract-build-mutation-intent.md`

## Why This Ticket Exists

While adding a deterministic partial-mutation verification scenario, the prompt:

```text
Repair this website with the smallest exact edits so the HTML, CSS, and
JavaScript remain valid and linked.
```

did not reach the approval gate. Talos treated the turn as read-only and blocked
the mutating tool calls before approval.

The immediate scenario was adjusted to use `Fix ...`, which is already
recognized. The underlying lexical gap remains.

## Problem

`MutationIntent` includes verbs such as:

```text
edit, modify, change, update, fix, rewrite, replace, redesign, write, create,
save, apply, add, remove, delete, refactor, put, implement
```

but does not include `repair`.

For users, `repair this site`, `repair index.html`, and `repair the broken app`
are explicit mutation requests. Treating them as read-only makes Talos look
unresponsive and prevents approval-gated repair work from starting.

## Goal

Recognize `repair` as an explicit mutation verb while preserving conservative
read-only protection for prompts such as "what repairs would you suggest?"

## Scope

### In scope

- Add `repair` to the core mutation intent vocabulary.
- Add TaskContract/MutationIntent tests for direct and polite repair prompts.
- Preserve read-only behavior for advisory/capability questions.
- Add or update one deterministic scenario if useful.

### Out of scope

- Broad natural-language intent overhaul.
- Weakening global read-only negations.
- Allowing mutation without approval.

## Proposed Work

1. Update `src/main/java/dev/talos/runtime/MutationIntent.java`.
2. Add tests:

   ```text
   repair this website -> mutationRequested true
   can you repair index.html -> mutationRequested true
   what repair would you make? -> mutationRequested false
   repair this file but do not change anything -> mutationRequested false
   ```

3. Confirm existing scoped-negation and build-intent tests still pass.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/test/java/dev/talos/runtime/MutationIntentTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "*MutationIntent*"
./gradlew.bat test --tests "*TaskContractResolver*"
```

Then widen:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
```

Manual:

- Run installed Talos in a disposable web workspace.
- Prompt `Repair this website...`.
- Confirm mutating tools can reach approval instead of being blocked as
  read-only.

## Acceptance Criteria

- `repair` starts an approval-gated mutation flow.
- Advisory repair questions remain read-only.
- Read-only negations still win.

## Completion Notes

Implemented on `ticket/talos-mutation-intent-repair-verb`.

`repair` was added to the core mutation verb regex only, not to loose substring
markers. That means direct and polite repair requests are mutation-capable, but
advisory questions such as `What repair would you make?` remain read-only.

Covered by:

```text
src/test/java/dev/talos/runtime/MutationIntentTest.java
src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java
```

Verification run:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos was rebuilt and manually run against
`local/manual-testing/qa-workspaces/broken-bmi-stale`.

Manual prompt:

```text
Repair index.html. Change the title to Repaired BMI. Use the file tools.
```

Talos reached an approval-gated `talos.edit_file` request instead of blocking
the turn as read-only. Approval was denied during verification, and no file
changed.

An earlier manual prompt produced malformed array-shaped protocol debris
(`[ , ]`). That separate display-hygiene issue is captured in:

```text
work-cycle-docs/tickets/done/talos-malformed-json-array-display-hygiene.md
```
