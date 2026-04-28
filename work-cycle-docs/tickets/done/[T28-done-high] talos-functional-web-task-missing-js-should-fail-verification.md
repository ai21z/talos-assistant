# [T28-done-high] Ticket: Functional Web Task Missing JS Should Fail Verification
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- work-cycle-docs/new-work.md
- docs/new-architecture/talos-harness-source-of-truth.md
- docs/new-architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T15-done-high] talos-readback-verification-wording.md
- work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md

## Why This Ticket Exists

The static verifier correctly catches incoherent three-file web apps. Manual testing found a gap for functional web tasks where Talos only creates or edits HTML/CSS and never creates JavaScript. The verifier can report that web coherence is unavailable instead of failing the task with concrete missing-functionality problems.

For a regular user asking for a working BMI calculator, `no task-specific verifier applicable` or `web coherence unavailable` is too weak.

## Problem

Reproduced transcript:

- `local/manual-testing/deep-review-2/nondev-bmi-title-only-transcript.txt`

Observed:

1. Talos updated only `index.html` for a request to make a working BMI calculator.
2. Final answer included:

```text
[File write/readback passed. No task-specific verifier was applicable, so task completion was not verified.]
```

3. Later partial repair produced:

```text
[Partial verification: static checks failed - web coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.]
```

Final files:

- `index.html` contained duplicate `weight`, `height`, and `result` IDs.
- No calculate button.
- No `scripts.js`.
- No JavaScript link.

For the user request, the deterministic result should be task incomplete with concrete missing elements, not merely readback-only or unavailable coherence.

## Goal

When the user asks for a functional calculator/web page, missing JavaScript/linkage/control elements should fail static verification with actionable problems even if the workspace does not yet expose a complete HTML/CSS/JS surface.

## Scope

In scope:
- Detect functional web-app/calculator task intent from `TaskContract`.
- If mutation touched web targets but required JS/control/linkage is absent, produce `FAILED` or `PARTIAL` static verification with concrete problems.
- Catch duplicate IDs relevant to form/calculator tasks.

Out of scope:
- Browser execution.
- General JS semantic correctness.
- Large framework/app analysis.

## Proposed Work

- Extend `StaticTaskVerifier` web verifier selection so calculator/functionality requests do not require all three file types before applying task-specific checks.
- Add checks for:
  - missing script file or inline script when functionality is requested,
  - missing script reference,
  - missing button or submit control,
  - duplicate IDs for expected controls/results.
- Keep wording honest: this is static verification, not browser/runtime proof.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for functional calculator task with:
  - only HTML/CSS present,
  - missing `scripts.js`,
  - duplicate IDs,
  - no calculate button.
- E2E scenario matching non-technical BMI prompt where Talos mutates only `index.html`.
- Manual Talos check in title-only BMI workspace.

## Acceptance Criteria

- Functional BMI/web task with no JS does not report readback-only as sufficient.
- Verifier returns actionable missing-JS/control problems.
- Duplicate expected IDs are detected.
- Final answer does not imply task completion.
- Focused tests and e2e pass.

## Evidence

Manual deep-review result on 2026-04-28:

- `nondev-bmi-title-only-transcript.txt` shows Talos partially editing HTML for a functional BMI calculator while verifier reported no applicable task-specific verifier or unavailable web coherence.

## Current Code Read

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationStatus.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/e2eTest/resources/scenarios/50-static-verifier-placeholder-web-app-fails.json`
- `src/e2eTest/resources/scenarios/62-repair-after-static-verification-failure-uses-verifier-context.json`
- `work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md`
- `work-cycle-docs/tickets/done/[T18-done-medium] talos-web-asset-idempotent-edit-checks.md`

## Planned Tests

- Add focused `StaticTaskVerifierTest` coverage for a functional BMI web task
  where only HTML/CSS exist and JavaScript is missing.
- Add focused `StaticTaskVerifierTest` coverage for duplicate expected IDs
  even when the JavaScript file is absent.
- Add one deterministic JSON e2e scenario where the model mutates only
  `index.html` for a functional BMI request and Talos reports concrete static
  verification failures instead of readback-only/unavailable wording.
- Run focused verifier tests, focused e2e, full `e2eTest`, and `check`.

## Implementation Summary

- Extended functional web-task detection to include `bmi` and common
  non-technical "make it work / actually work" phrasing when the task is
  already a mutating web-surface request.
- Added partial functional-web verification before the generic
  "HTML/CSS/JS surface unavailable" fallback.
- For partial HTML/CSS web surfaces, static verification now reports concrete
  missing JavaScript behavior, missing JavaScript links or referenced JS files,
  duplicate HTML IDs, and calculator/form control problems where applicable.
- Reused the same calculator/form control checker for complete and partial
  web surfaces.
- Added deterministic e2e scenario 63 for a non-technical BMI page request
  where the model mutates only `index.html` and omits JavaScript.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon`
  -> FAIL, expected failures because the verifier only reported generic web
  coherence unavailability and did not report missing JavaScript or duplicate
  IDs on partial web surfaces.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon`
  -> PASS.
- Focused e2e RED:
  `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.functionalWebTaskMissingJavascriptFailsVerification" --no-daemon`
  -> FAIL, expected failure because "BMI page / make it actually work" did not
  trigger task-specific web verification and fell back to readback-only wording.
- Focused e2e GREEN:
  `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.functionalWebTaskMissingJavascriptFailsVerification" --no-daemon`
  -> PASS.
- `./gradlew.bat e2eTest --no-daemon` -> PASS.
- `./gradlew.bat check --no-daemon` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket changed post-apply static task verification, so
focused red/green unit coverage, focused red/green deterministic e2e, full
`e2eTest`, hard gate `check`, and installed manual Talos verification were
run. Candidate loop was not run; no versioned candidate was declared and
`CHANGELOG.md` was not updated.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, one non-technical BMI prompt,
approval `a`, and `/q` into the installed Talos CLI.

Workspace:
`local/manual-workspaces/T28/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
Hi, I don't really know coding. I have this little BMI page here and it only shows a title. Can you make it actually work for me? Please update the local files. Use file tools; do not just show code.
```

Approval choice:
`a`

Observed tools:
`talos.list_dir`, `talos.read_file`, `talos.write_file`

Files changed:
`script.js` was created in `local/manual-workspaces/T28/`.

Output file:
`local/manual-testing/T28-output.txt`

Pass/fail:
PASS for installed CLI truthfulness/no-overclaim behavior.

Notes:
The live model created `script.js`, so the installed run did not reproduce the
missing-JavaScript branch directly. Talos still ran functional-web static
verification and refused to claim completion, reporting:
`Task incomplete: Static verification failed - Calculator/form task is missing a result output element.`
The exact missing-JavaScript branch is covered deterministically by
`StaticTaskVerifierTest.functionalCalculatorTaskFailsWithConcreteProblemsWhenJavaScriptIsMissing`
and scenario 63.

## Known Follow-Ups

- The live model repaired JavaScript but left the page with no result output
  element. T23's bounded repair context can now carry that verifier finding,
  but a future repair-quality ticket should improve the model's first-pass
  tendency to add JavaScript without also updating the DOM.
- The T28 verifier is static only; it still does not execute browser runtime
  behavior or prove JavaScript math correctness.

## Commit

Commit message:
`T28: fail functional web verification when JavaScript is missing`

Commit hash:
Recorded in final handoff after commit creation.
