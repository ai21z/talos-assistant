# [done] Ticket: Windows-Aware Expected Target Normalization
Date: 2026-04-27
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed static verification treating `Index.html` as different
from the successfully mutated `index.html`:

```text
Index.html: expected target was not successfully mutated.
```

On Windows, that is misleading because the filesystem is normally
case-insensitive.

## Problem

Expected target matching normalizes slashes but not platform case semantics.
This creates false static-verification failures when the user capitalizes a path
differently from the actual file.

## Goal

Normalize expected target matching according to platform path semantics.

## Scope

### In scope

- Normalize path separators consistently.
- On Windows, compare expected and mutated targets case-insensitively.
- Preserve case-sensitive behavior on platforms where that is the safer
  default.
- Add tests that do not depend on the developer machine being Windows where
  possible.

### Out of scope

- Broad filesystem abstraction rewrite.
- Changing actual file path casing on disk.
- Index path normalization changes outside the verifier.

## Proposed Work

1. Add a small path matching helper for static verifier target comparisons.
2. Make platform behavior explicit and testable.
3. Update expected-target verification to use that helper.
4. Add regression coverage for `Index.html` vs `index.html`.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`

## Test / Verification Plan

- Unit test path normalization helper.
- Unit test expected target verification with mismatched casing.
- Run focused static verifier tests.

## Current Code Read

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/main/java/dev/talos/runtime/task/TaskContract.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`

## Planned Tests

- Add focused unit coverage for explicit case-insensitive target matching
  (`Index.html` vs `index.html`) without depending on the host OS.
- Add focused verifier coverage proving expected targets with case-only
  differences do not fail when Windows-style matching is requested.
- Run `StaticTaskVerifierTest`, full `e2eTest`, and `check` because this
  changes verification truthfulness.

## Acceptance Criteria

- On Windows semantics, `Index.html` matches mutated `index.html`.
- Slash normalization still works.
- The verifier no longer reports false missing-target failures for simple case
  differences on Windows.

## Implementation Summary

- Added a small expected-target matching helper in `StaticTaskVerifier`.
- Kept slash normalization unchanged and made case handling explicit.
- `verifyExpectedTargets(...)` now uses case-insensitive target comparison on
  Windows and preserves case-sensitive comparison elsewhere.
- Added a deterministic Windows-only e2e scenario proving an uppercase
  `Index.html` request does not produce a false missing-target verification
  problem when the tool mutates lowercase `index.html`.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.expectedTargetMatchingCanUseWindowsCaseInsensitiveSemantics" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.expectedTargetFromContractMatchesCaseDifferenceOnWindows"`
  -> FAIL at compile because `StaticTaskVerifier.expectedTargetMatches(...)`
  did not exist.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.expectedTargetMatchingCanUseWindowsCaseInsensitiveSemantics" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.expectedTargetFromContractMatchesCaseDifferenceOnWindows"`
  -> PASS.
- `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"`
  -> PASS.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.windowsExpectedTargetCaseNormalization"`
  -> PASS.
- `./gradlew.bat e2eTest` -> PASS.
- `./gradlew.bat check` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket changed static verification truthfulness, so focused
unit tests, a focused deterministic e2e scenario, full `e2eTest`, hard gate
`check`, and installed manual Talos verification were run. Candidate loop was
not run because this is one ticket in the T11-T18 batch, not a declared
candidate release.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, the prompt, approval `a`, and
`/q` into the installed Talos CLI.

Workspace:
`local/manual-workspaces/T17/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
No no I want to create a 3 files BMI calculator. Index.html, styles.css and scripts.js so I can have some functionality. For scripts.js, write exactly this placeholder line and nothing else: // Your JavaScript logic here. Use file tools; do not just show code.
```

Approval choice:
`a`

Observed tools:
`talos.write_file`

Files changed:
`index.html`, `styles.css`, `scripts.js` in `local/manual-workspaces/T17/`.

Output file:
`local/manual-testing/T17-output.txt`

Pass/fail:
PASS

Notes:
The installed CLI used lowercase `index.html` as the mutation target even
though the user request said `Index.html`. Static verification reported real
file-content problems (`index.html` and `styles.css` were empty) and did not
report `Index.html: expected target was not successfully mutated.`

## Known Follow-Ups

- Scoped negation remains separate: a prompt like `Fix only styles.css. Do not
  change index.html or scripts.js.` can still be classified too read-only and
  should be handled by a new scoped mutation-intent ticket.
