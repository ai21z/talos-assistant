# [done] Ticket: Generic Web-App Static Verifier v0
Date: 2026-04-27
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/talos-static-verifier-web-app-scope-and-wording.md`
- `work-cycle-docs/tickets/done/talos-read-only-web-diagnostics-static-grounding.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

The final manual-test workspace was not a functioning BMI calculator:

- `index.html` had no form, inputs, button, or script tag.
- `scripts.js` contained only placeholder text.
- `styles.css` contained useful form styles that the HTML did not use.

Yet some turns reported readback/static success because the verifier only knew
that a target file existed and was readable.

## Problem

Talos has early web coherence checks, but they are not strong enough for a
basic multi-file web-app task. A user asking for a functioning web app expects
the HTML, CSS, and JavaScript to be connected and non-placeholder, not merely
present on disk.

## Goal

Add a generic static web-app verifier v0. It should not be BMI-specific by
default, but it should catch obvious HTML/CSS/JS wiring failures for small local
web workspaces.

## Scope

### In scope

- Check expected web files exist when a web-app task names or implies them.
- Check `index.html` links CSS files that exist.
- Check `index.html` links JavaScript files that exist.
- Flag duplicate stylesheet/script references.
- Flag placeholder or near-placeholder JS/CSS/HTML content.
- Check JS `getElementById` / selector references exist in HTML.
- For calculator/form-like task families, check for at least:
  - a form or equivalent input container,
  - weight/height-style inputs when requested,
  - a submit/calculate button,
  - a result output element.

### Out of scope

- Browser automation.
- Executing JavaScript.
- Full HTML/CSS/JS parsing with a new framework dependency.
- A hardcoded BMI-only production verifier.

## Proposed Work

1. Extend `StaticTaskVerifier` through a small web-app task family check or a
   dedicated verifier strategy.
2. Reuse simple static parsing already present for selector/linkage checks.
3. Keep checks explainable and deterministic.
4. Add a transcript-shaped BMI repair scenario as an end-to-end guard.
5. Add smaller unit tests for each static rule.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Test / Verification Plan

- Unit tests:
  - missing JS link fails,
  - missing CSS link fails,
  - duplicate links fail,
  - placeholder JS fails,
  - JS references missing DOM IDs fails,
  - basic valid HTML/CSS/JS app passes.
- E2E scenario:
  - initial broken BMI files,
  - model writes partial app,
  - verifier refuses to claim task completion.

## Current Code Read

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Planned Tests

- Add focused verifier unit coverage for duplicate CSS/JS references,
  placeholder JavaScript, and calculator/form-like tasks missing required
  controls/output wiring.
- Add a deterministic e2e scenario where a partial BMI repair is rejected by
  the static web verifier.
- Run focused verifier tests, `e2eTest`, and `check` because this changes
  task-completion truthfulness.

## Acceptance Criteria

- A web-app task cannot be marked task-verified if HTML does not link the JS.
- Placeholder `scripts.js` fails verification.
- Duplicate stylesheet/script references fail verification.
- HTML/CSS/JS linkage failures are reported in user-visible final answers.
- Generic non-web file writes are not forced through web-app verification.

## Implementation Summary

- Extended `StaticTaskVerifier` web coherence checks to recognize explicit
  web filenames/extensions such as `index.html`, `.css`, and `.js` as broad
  web-app task signals.
- Added duplicate stylesheet/script reference detection while preserving linked
  asset selection for primary CSS/JS files.
- Added obvious near-placeholder content checks for HTML, CSS, and JavaScript
  files in small web-app verification.
- Added narrow calculator/form structure checks for form-like web tasks:
  form/input container, requested weight/height inputs, submit/calculate button,
  and result output element.
- Added a deterministic e2e scenario where a placeholder `scripts.js` prevents
  Talos from claiming static web-app completion.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"`
  -> FAIL, expected failures for duplicate linked assets, placeholder
  JavaScript, and missing calculator/form controls.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"`
  -> initially failed one pre-existing fixture that was valid for linked-CSS
  preference but incomplete for the new calculator/form rule; fixture updated
  to remain focused on linked-CSS behavior.
- `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"`
  -> PASS.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.staticVerifierPlaceholderWebAppFails"`
  -> initially surfaced the known T17 case mismatch (`Index.html` vs
  `index.html`), then a broad-web-task detection gap for explicit filenames.
  The scenario prompt was scoped away from T17 and broad-web detection was
  extended.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.staticVerifierPlaceholderWebAppFails"`
  -> PASS.
- `./gradlew.bat e2eTest` -> PASS.
- `./gradlew.bat check` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket changed post-apply task-completion verification, so
focused unit tests, focused deterministic e2e, full `e2eTest`, hard gate
`check`, and installed manual Talos verification were run. Candidate loop was
not run because this is one ticket in the T11-T18 batch, not a declared
candidate release.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, prompts, approval `a`, and `/q`
into the installed Talos CLI. Follow-up installed runs appended to the same
transcript.

Workspace:
`local/manual-workspaces/T16/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
Create a modern BMI calculator website in exactly three files: index.html, styles.css, and scripts.js. For scripts.js, write exactly this placeholder line and nothing else: // Your JavaScript logic here. Use file tools; do not just show code.
```

Follow-up prompts:
```text
Create the missing styles.css and scripts.js files for this BMI calculator workspace. For scripts.js, write exactly this single line and nothing else: // Your JavaScript logic here. Use file tools; do not just show code.

Fix only styles.css with real CSS for this BMI calculator web app. Do not change index.html or scripts.js. Use file tools; do not just show code.
```

Approval choice:
`a`

Observed tools:
`talos.write_file`, then `write_file`; the third follow-up was classified
read-only and used `talos.read_file`, `talos.grep`, and `talos.list_dir`.

Files changed:
`index.html`, `styles.css`, `scripts.js` in `local/manual-workspaces/T16/`.

Output file:
`local/manual-testing/T16-output.txt`

Pass/fail:
PASS for installed CLI truthfulness/no-overclaim behavior.

Notes:
The live model did not produce a clean placeholder-only failure: first it wrote
only `index.html`, then it wrote empty `styles.css` plus placeholder
`scripts.js`. In both mutation runs, installed Talos reported
`Task incomplete: Static verification failed` and did not claim static
verification passed. The exact placeholder-JavaScript branch is covered
deterministically by scenario 50. The third follow-up exposed a non-blocking
intent-classification issue: `Fix only styles.css... Do not change index.html
or scripts.js` was treated as `DIAGNOSE_ONLY` and stayed read-only. That should
be considered for a later intent/scoped-negation ticket, but it does not block
the T16 verifier work.

## Known Follow-Ups

- T17 still needs Windows/case-insensitive expected-target normalization; the
  first T16 e2e draft surfaced this with `Index.html` vs `index.html`.
- A future intent ticket should investigate why the installed CLI classified
  `Fix only styles.css... Do not change index.html or scripts.js` as
  `DIAGNOSE_ONLY` instead of an apply-capable scoped mutation.
