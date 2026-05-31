# [done] Ticket: Scoped Negation Mutation Intent
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-task-contract-build-mutation-intent.md`

## Why This Ticket Exists

Manual installed-Talos QA found that a straightforward edit request was
classified as read-only:

```text
Change TODO to DONE in notes.txt. Use the edit tool and do not modify anything else.
```

Observed trace:

```text
contract: READ_ONLY_QA mutationAllowed=false verificationRequired=false
blocked: task-contract read-only denied talos.edit_file
```

The file was not changed.

## Problem

The prompt contains an explicit mutation request:

```text
Change TODO to DONE in notes.txt.
```

but it also contains scoped safety language:

```text
do not modify anything else
```

`MutationIntent.looksExplicitMutationRequest(...)` currently treats broad
phrases such as `do not modify` as global read-only negations. That causes
normal scoped edit instructions to suppress mutation intent completely.

Current relevant code:

```text
src/main/java/dev/talos/runtime/MutationIntent.java
READ_ONLY_NEGATIONS includes "do not modify", "do not change", ...
```

This is a code-level classifier bug, not a model-quality issue.

## Goal

Allow explicit scoped mutation requests while preserving read-only protection
for true no-change prompts.

Talos should distinguish:

```text
Edit notes.txt to replace TODO with DONE. Do not modify anything else.
```

from:

```text
Inspect notes.txt. Do not modify anything.
```

## Scope

### In scope

- Refine read-only negation handling in `MutationIntent`.
- Recognize scoped phrases such as:
  - `do not modify anything else`
  - `do not change anything else`
  - `do not edit any other files`
  - `only change notes.txt`
- Add tests through `TaskContractResolver`, not just `MutationIntent`.
- Ensure scoped mutation still requires approval.

### Out of scope

- Broad LLM intent classifier.
- Planner implementation.
- Weakening read-only `do not change anything` instructions.
- Changing approval policy.

## Proposed Work

1. Split read-only negations into:

   ```text
   global no-mutation instructions
   scoped mutation limiters
   ```

2. Let explicit mutation verbs win when the negation clearly scopes other
   files/targets:

   ```text
   do not modify anything else
   do not modify other files
   only modify X
   ```

3. Preserve read-only behavior for:

   ```text
   do not modify anything
   do not change files
   inspect only
   without changing
   ```

4. Add direct tests:

   - `Change TODO to DONE in notes.txt. Do not modify anything else.` ->
     `FILE_EDIT`, mutation allowed
   - `Edit notes.txt to replace TODO with DONE. Do not modify anything else.` ->
     `FILE_EDIT`, mutation allowed
   - `Check notes.txt. Do not modify anything.` -> read-only
   - `What would you change? Do not modify files.` -> read-only

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/runtime/MutationIntentTest.java` if present
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
```

Then run:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
```

Manual installed verification:

- Use a disposable workspace with `notes.txt`.
- Prompt:

  ```text
  Change TODO to DONE in notes.txt. Use the edit tool and do not modify anything else.
  ```

- Expected:
  - contract is `FILE_EDIT`
  - approval is requested
  - approved edit changes only `notes.txt`
  - static verification passes or reports the narrow target clearly

## Acceptance Criteria

- Scoped no-other-files language does not suppress explicit mutation intent.
- True read-only negations remain read-only.
- The fix is covered by deterministic tests and installed manual verification.
- Approval and scope safety remain unchanged.

## Completion Notes

Implemented on `ticket/talos-scoped-negation-mutation-intent`.

`MutationIntent` now treats no-other-target phrases such as `do not modify
anything else` and `do not edit any other files` as scoped limiters instead of
global read-only negations. True no-mutation instructions such as `do not
modify anything`, `do not modify files`, and `without changing` remain
read-only.

Also added support for `Only change ...` style explicit edit requests.

Verification completed:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.scopedNegationAllowsExplicitEdit"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos was rebuilt and reinstalled. Manual verification in
`local/manual-testing/qa-workspaces/simple-text-edit` confirmed:

- `Change TODO to DONE in notes.txt. Use the edit tool and do not modify
  anything else.` resolves to `FILE_EDIT`
- approval is requested
- only `notes.txt` changes
- static target/readback verification passes
