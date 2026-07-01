# [done] Ticket: TaskContract Build/Make Mutation Intent
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-source-of-truth.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`
Related tickets:
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-mutation-intent-guard.md`
- `work-cycle-docs/tickets/done/talos-read-only-turns-should-avoid-unsolicited-mutation-attempts.md`

## Why This Ticket Exists

Installed Talos verification on 2026-04-26 showed that normal user requests to
build/create a website can be classified as read-only. That breaks the
execution contract before the model/tool loop has a chance to do the right
thing.

This is not just a prompt-quality issue. The runtime produced the wrong
`TaskContract`.

## Problem

The prompt:

```text
Can you build a small BMI calculator website here with separate CSS and JavaScript files? Use the file tools if you can; do not just show code.
```

was resolved as:

```text
type: READ_ONLY_QA
mutationAllowed: false
```

Executable JShell verification against the current classes confirmed:

```text
Can you build ... -> mutationIntent=false, type=READ_ONLY_QA, mutationAllowed=false
Ah okay can you make ... -> mutationIntent=false, type=READ_ONLY_QA, mutationAllowed=false
Can you make it? -> mutationIntent=true, type=FILE_EDIT, mutationAllowed=true
```

Current root causes:

- `MutationIntent.REQUEST_PATTERNS` does not include `build`.
- The anchored regex misses conversational prefixes such as `Ah okay can you make...`.
- `MARKERS` has `make it`, `make the`, `make this`, but not `make a`.
- Broad web creation wording such as "build a website", "make a calculator",
  and "create a page/app/site" is not represented as a first-class mutation
  shape.

## Goal

Make `TaskContractResolver` correctly classify common local creation/build
requests as mutating apply work, while preserving conservative read-only
classification for questions about capabilities, explanations, and diagnostics.

## Scope

### In scope

- Add mutation-intent coverage for common build/create/make website/app/file
  phrasing.
- Handle polite/conversational prefixes before explicit mutation requests.
- Add direct unit tests for the exact installed-transcript prompts.
- Add a deterministic scenario proving that a build/create request reaches an
  apply-capable contract rather than read-only phase.
- Keep the existing read-only safety guards unchanged.

### Out of scope

- Per-turn native tool-surface filtering. That is tracked separately.
- Broad natural-language planning.
- Browser/shell/test-runner verification.
- Weakening approval requirements.

## Proposed Work

1. Extend `MutationIntent` verb coverage.

   Include `build`, and likely `generate`, `put`, `set up`, `scaffold`, and
   "make a/make an" when paired with a workspace artifact such as website,
   page, app, component, file, calculator, stylesheet, or script.

2. Add safe prefix tolerance.

   Accept leading conversational particles before explicit mutation forms, for
   example:

   ```text
   ah okay can you make...
   okay build...
   please can you create...
   ```

   Keep this bounded. Do not turn every sentence containing "make" into a
   mutation request.

3. Preserve read-only negatives.

   Prompts like these must remain read-only:

   ```text
   What can you build?
   Can you explain how to build a BMI calculator?
   Why did you not make changes?
   Show me how to make one, do not edit files.
   ```

4. Feed the fix through `TaskContractResolver` tests, not only
   `MutationIntent` tests.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- possibly `src/e2eTest/resources/scenarios/`
- possibly `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
```

Required cases:

- `Can you build a small BMI calculator website...` -> `FILE_CREATE` or
  apply-capable mutation contract.
- `Ah okay can you make a cool looking BMI calculator website...` ->
  apply-capable mutation contract.
- `Can you make it?` remains mutation-capable when conversation context already
  implies a pending creation/edit.
- capability/explanation prompts containing `build` remain read-only.
- explicit `do not change anything` still wins as read-only.

Installed verification:

- Run installed Talos in `local/playground/horror-synth-site`.
- Use the exact BMI prompt.
- Confirm `/prompt last` no longer shows `READ_ONLY_QA` /
  `mutationAllowed: false`.
- Confirm Talos reaches approval or a valid mutation failure path, not a
  read-only phase block.

## Acceptance Criteria

- Common "build/make/create a website/app" prompts are not misclassified as
  read-only.
- Read-only diagnostic prompts remain read-only.
- The fix is covered by deterministic tests using the exact observed prompt
  shapes.
- Runtime safety still depends on approval and phase policy after
  classification.
