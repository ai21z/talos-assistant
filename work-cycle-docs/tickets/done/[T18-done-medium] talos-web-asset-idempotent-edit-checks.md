# [done] Ticket: Web Asset Edits Should Be Idempotent
Date: 2026-04-27
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed Talos inserting duplicate stylesheet links by repeatedly
editing around the same anchor:

```html
<link rel="stylesheet" href="styles.css">
<link rel="stylesheet" href="styles.css">
<link rel="stylesheet" href="styles.css">
```

The repeated edit technically succeeded, but it made the file worse.

## Problem

After a successful edit, the same semantic anchor may still exist inside the
new content. A model can repeat the same edit and duplicate assets, scripts, or
DOM elements. The current runtime can report the edit as successful even though
the semantic result is not idempotent.

## Goal

Detect and prevent or downgrade obvious duplicate web-asset mutations.

## Scope

### In scope

- Detect duplicate identical stylesheet links.
- Detect duplicate identical script tags.
- Detect duplicate IDs in simple HTML files.
- Surface duplicate-web-asset problems in verification results.
- Consider loop-level detection for repeated successful edits to the same
  semantic anchor when practical.

### Out of scope

- Full DOM parser dependency.
- Browser validation.
- Blocking legitimate repeated CSS selectors.

## Proposed Work

1. Add duplicate asset checks to the web-app verifier.
2. Add tests around duplicate `<link href="styles.css">` and
   `<script src="scripts.js">`.
3. Consider whether `ToolCallExecutionStage` should flag repeated semantic
   insertions during the same turn.
4. Ensure final answer cannot call a task complete when duplicate assets remain.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for duplicate stylesheet/script/id detection.
- E2E scenario where the model repeats a stylesheet insertion.
- Confirm duplicate detection appears in the final answer.

## Current Code Read

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/e2eTest/resources/scenarios/17-static-verifier-selector-fails-after-wrong-edit.json`
- `src/e2eTest/resources/scenarios/18-static-verifier-selector-passes-after-cta-fix.json`
- `src/e2eTest/resources/fixtures/mini-site/index.html`

## Planned Tests

- Add focused unit coverage proving duplicate HTML IDs fail web-app static
  verification.
- Add deterministic e2e coverage for a repeated stylesheet insertion, using
  existing T16 duplicate-link verification.
- Run `StaticTaskVerifierTest`, focused e2e, full `e2eTest`, and `check`
  because this affects task-completion truthfulness.

## Acceptance Criteria

- Duplicate identical stylesheet links fail web-app static verification.
- Duplicate identical script tags fail web-app static verification.
- Duplicate HTML IDs are flagged.
- The task is not marked complete while these duplicates remain.

## Implementation Summary

- Added duplicate HTML ID detection to `StaticTaskVerifier` by preserving ID
  occurrences alongside the existing unique ID set used for selector matching.
- Reused the T16 duplicate stylesheet/script checks as the central
  post-apply verifier path for idempotent web-asset edit failures.
- Added a deterministic e2e scenario where an edit duplicates the stylesheet
  link and the final answer surfaces static verification failure.
- Considered loop-level repeated semantic insertion blocking in
  `ToolCallExecutionStage`; not implemented in this ticket because the central
  verifier now catches the semantic workspace state without introducing a
  fragile edit-shape heuristic.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.broadWebAppBuildFailsWhenHtmlIdsAreDuplicated"`
  -> FAIL, expected failure because duplicate IDs were not reported.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.broadWebAppBuildFailsWhenHtmlIdsAreDuplicated"`
  -> PASS.
- `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"`
  -> PASS.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.repeatedStylesheetInsertionFailsVerification"`
  -> PASS.
- `./gradlew.bat e2eTest` -> PASS.
- `./gradlew.bat check` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket changed post-apply static verification, so focused
unit tests, focused deterministic e2e, full `e2eTest`, hard gate `check`, and
installed manual Talos verification were run. Candidate loop was not run
because this is one ticket in the T11-T18 batch, not a declared candidate
release.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, prompts, approval `a`, and `/q`
into the installed Talos CLI. Multiple installed prompts were appended to the
same transcript while isolating the verifier behavior.

Workspace:
`local/manual-workspaces/T18/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
Edit index.html so the HTML, CSS, and JavaScript web assets are wired cleanly by duplicating the existing stylesheet link. Use read_file then edit_file; do not just show code.
```

Earlier exploratory prompts:
```text
In index.html, insert one duplicate line immediately after the existing stylesheet line: <link rel="stylesheet" href="style.css">. Use the file edit tool; do not just show code.

Edit index.html. Replace the single stylesheet link line <link rel="stylesheet" href="style.css"> with two identical stylesheet link lines for style.css. Use file tools; do not just show code.
```

Approval choice:
`a`

Observed tools:
`read_file`, `edit_file`

Files changed:
`index.html` in `local/manual-workspaces/T18/`.

Output file:
`local/manual-testing/T18-output.txt`

Pass/fail:
PASS for T18 duplicate-asset verifier behavior.

Notes:
The successful installed check edited `index.html`, created a duplicate
stylesheet link, and Talos reported:
`Task incomplete: Static verification failed - HTML links CSS file more than once: style.css`.
It did not claim static verification passed.

Two exploratory prompts exposed non-blocking intent/contract issues outside
T18's verifier scope:
- `insert one duplicate line...` was classified `READ_ONLY_QA` and blocked
  `edit_file`.
- Naming the literal `style.css` asset inside the edit instruction made
  expected-target extraction require `style.css` to be mutated, which masked
  the duplicate-link verifier until the prompt was rewritten.

## Known Follow-Ups

- Add a scoped mutation-intent ticket so "insert ..." and "fix only X; do not
  change Y" remain apply-capable while limiting mutation scope.
- Add an expected-target extraction refinement ticket so filenames mentioned
  as referenced assets are not always treated as files that must be mutated.
