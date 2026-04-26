# [done] Ticket: Static Verification Failure Repair Or Downgrade
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `work-cycle-docs/tickets/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/talos-minimal-task-outcome.md`

## Why This Ticket Exists

Manual installed-Talos QA found that the static verifier can correctly detect a
failed task, but the runtime does not yet act on that failure.

Observed transcript:

```text
[Static verification failed: script.js: expected target was not successfully mutated.;
Expected web-app build to successfully mutate a JavaScript file.; web coherence could
not be checked because the workspace does not expose a small HTML/CSS/JS ...]

[ok] Created index.html (26 lines, 643 bytes)
[ok] Created style.css (20 lines, 277 bytes)
```

The user requested a modern functioning BMI calculator website with separate
HTML, CSS, and JavaScript files. Talos created only `index.html` and
`style.css`; `script.js` was missing.

## Problem

The static verifier produced the right structured signal, but the end-of-turn
policy treated the turn as finished after the tool loop stopped.

This is an architecture gap:

- `StaticTaskVerifier` can identify missing expected targets.
- `ExecutionOutcome` / `TaskOutcome` can carry failed verification.
- The runtime does not yet convert failed verification into a bounded repair
  attempt or an explicit incomplete-task final answer.

The result is better than a silent false success, but still below the Talos
discipline target. A verified failure should change behavior, not only appear
as a line in the transcript.

## Goal

When post-apply static verification fails for a user-requested mutation, Talos
must either:

1. make one bounded repair attempt using the verifier facts, or
2. downgrade the final outcome to clearly incomplete/failed and tell the user
   exactly what was not completed.

It must not present a normal-looking completion summary for a task whose
required static facts failed.

## Scope

### In scope

- Use structured `TaskOutcome` / `TaskVerificationResult` state instead of
  parsing human summaries.
- Add a bounded repair-or-downgrade policy after static verification failure.
- Start with high-confidence static failures:
  - expected target was not successfully mutated
  - expected web-app JavaScript/CSS file missing
  - small-web coherence cannot run because required files are absent
- Ensure partial creation summaries are visibly incomplete when verification
  fails.
- Add scenario coverage for a multi-file web-app creation where one required
  file is omitted.

### Out of scope

- Browser execution.
- Shell/test-runner verification.
- Full semantic verification of BMI math or design quality.
- Unbounded retry loops.
- New framework dependencies.

## Proposed Work

1. Inspect the current integration points:

   ```text
   AssistantTurnExecutor.shapeAnswerAfterToolLoop(...)
   ExecutionOutcome.fromToolLoop(...)
   TaskOutcome
   StaticTaskVerifier
   ToolCallLoop.ToolOutcome
   ```

2. Add a small policy method after verification:

   ```text
   if mutation requested AND mutation happened AND verification failed:
     if failure is repairable and no repair already attempted:
       reprompt once with verifier facts and required missing targets
     else:
       mark outcome as incomplete/failed and render that prominently
   ```

3. Keep failure discipline bounded:

   - maximum one verifier-driven repair attempt
   - no repeated approval prompts for the same failed target unless a new
     mutation is actually proposed
   - no repair attempt after approval denial

4. Make final answer wording harder to misread:

   - "Created index.html and style.css, but the requested script.js was not
     created, so the website is not verified complete."
   - avoid a bare successful task summary when verification failed

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/outcome/TaskOutcome.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"
```

Scenario coverage:

```text
multi-file web creation where script.js is requested but omitted
expected outcome: verifier failure produces repair or explicit incomplete status
```

Manual installed verification:

- Use a disposable workspace with only `README.md`.
- Ask Talos to create a BMI calculator with separate HTML/CSS/JS.
- Approve writes.
- Confirm the final answer and filesystem agree:
  - if all files exist and static coherence passes, task may be verified
  - if any required file is missing, final answer must say incomplete/failed

## Acceptance Criteria

- A failed static verifier result changes runtime behavior.
- Missing expected targets are not hidden behind successful mutation summaries.
- Multi-file creation tasks cannot end as normal completion when a requested
  target was not created.
- Repair attempts are bounded and do not spiral.
- Existing approval-denial behavior remains unchanged.

## Completion Notes

Implemented the bounded downgrade slice on
`ticket/talos-static-verification-failure-repair-or-downgrade`.

When post-apply static verification fails, the final answer now starts with an
explicit incomplete outcome:

```text
[Task incomplete: Static verification failed - ...]
```

It also states that the requested task is not verified complete and lists the
first unresolved static verification problems before any successful mutation
summaries. This keeps applied file writes visible while preventing them from
looking like completed task evidence.

This ticket intentionally does not add an automatic repair loop. Bounded repair
remains future work after the downgrade behavior is reliable.

Verification completed:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.staticVerifierMissingScriptDowngradesIncomplete"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos was rebuilt and reinstalled. Manual verification in
`local/manual-testing/qa-workspaces/create-bmi-site` reproduced the missing
asset shape: the model wrote only `index.html`, and Talos reported:

- `Task incomplete: Static verification failed`
- missing `style.css`
- missing `script.js`
- no `Static verification: passed` claim

Observed unrelated display debt:

- stray streamed `}` characters appeared before approval. This belongs to the
  existing streaming protocol display hygiene ticket, not this verifier outcome
  fix.
