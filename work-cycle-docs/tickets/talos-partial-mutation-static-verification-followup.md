# [done] Ticket: Partial Mutation Static Verification Follow-Up
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/talos-partial-edit-reread-repair-policy.md`

## Why This Ticket Exists

Installed Talos QA against a deliberately broken BMI website produced a safe
partial-mutation summary, but the remaining workspace problems were not
surfaced by static verification.

Observed after approving edits:

```text
[Truth check: some requested file changes succeeded and some failed.]

Succeeded:
- index.html: ...
- script.js: ...
Failed:
- index.html: Invalid talos.edit_file call: missing required parameter `new_string`.
```

The final answer was truthful about partial success. However, the workspace
still had static HTML/CSS problems:

```text
<button type="submit">Calculate BMI</button
<script src="script.js"></script
calculator-container { ... }
```

Because the turn was partial, post-apply static verification did not run and
the answer did not name these remaining local facts.

## Problem

`ExecutionOutcome` currently runs `StaticTaskVerifier` only when the completion
status is `COMPLETE`. That is conservative, but it means a partial mutation can
avoid useful static diagnostics even when some files changed and the task is
known incomplete.

This is not a false-success bug; Talos already says the turn is partial. The
gap is evidence quality: the user sees failed tool arguments, but not the
static workspace problems that remain after the successful edits.

## Goal

For partial mutation turns with successful workspace edits, run a bounded
static verification pass and include concise remaining static problems in the
partial result when applicable.

## Scope

### In scope

- Run static verification for `PARTIAL` mutation turns when at least one
  mutation succeeded and the task contract requires verification.
- Keep the final completion status `PARTIAL`, not `COMPLETE`.
- Add a compact "Remaining static problems" section or equivalent under the
  partial summary.
- Ensure failed tool arguments remain visible.
- Add deterministic scenario coverage for a partial web repair with malformed
  HTML/CSS still present.

### Out of scope

- Claiming semantic task completion after partial success.
- Browser execution.
- HTML parser dependencies.
- Broad planner or TaskContract expansion.

## Proposed Work

1. Adjust the verification gate in `ExecutionOutcome` so partial mutation turns
   with successful mutations can produce a `TaskVerificationResult`.
2. Keep the status mapping distinct:

   ```text
   PARTIAL + verification FAILED -> partial answer with static problems
   PARTIAL + verification PASSED -> still partial if failed tool calls remain
   ```

3. Extend partial summary shaping in `AssistantTurnExecutor` or central outcome
   assembly without adding scattered truth patches.
4. Add focused tests in `ExecutionOutcomeTest`.
5. Add a JSON e2e scenario for partial BMI repair with unresolved static
   problems.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Then widen:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed verification:

- Use the broken BMI QA workspace.
- Approve edits.
- Confirm the final answer remains partial and also names remaining static
  problems when malformed HTML/CSS remains.

## Acceptance Criteria

- Partial mutation turns remain explicitly partial.
- Static verification can still surface unresolved local facts after partial
  edits.
- The answer does not hide failed tool arguments.
- No false completion claim is introduced.

## Completion Notes

Implemented on `ticket/talos-partial-mutation-static-verification-followup`.

The central `ExecutionOutcome` path now runs bounded static verification for
partial mutation turns with successful mutations and a verification-required
task contract. Failed verification no longer upgrades or downgrades the turn
out of `PARTIAL`; instead the answer receives a concise partial-verification
annotation and keeps the failed tool argument summary visible.

Covered by:

```text
src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java
src/e2eTest/resources/scenarios/30-partial-mutation-static-verification-surfaces-problems.json
src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java
```

Verification run:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.partialMutationStaticVerificationSurfacesProblems"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos was rebuilt and manually run against
`local/manual-testing/qa-workspaces/broken-bmi-stale`. The live run did not
reach a successful partial mutation; it stopped safely before approval after
repeated invalid `edit_file` arguments. The transcript is saved in
`local/manual-testing/test-output`, and the newly observed gaps were captured as:

```text
work-cycle-docs/tickets/talos-read-only-web-diagnostics-static-grounding.md
work-cycle-docs/tickets/talos-mutation-intent-repair-verb.md
work-cycle-docs/tickets/talos-empty-edit-args-recovery-v2.md
```
