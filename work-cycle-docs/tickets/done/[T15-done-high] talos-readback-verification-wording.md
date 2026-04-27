# [done] Ticket: Readback Passed Must Not Mean Task Verified
Date: 2026-04-27
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-outcome.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/talos-static-verifier-web-app-scope-and-wording.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed Talos saying:

```text
Static verification: passed - Target/readback checks passed for 1 mutated
target(s); no task-specific static verifier was applicable.
```

But the mutated file was a placeholder `scripts.js`, or only one file was
updated for a multi-file BMI calculator task. The filesystem write/readback
passed; the task did not.

## Problem

The current wording lets a user interpret "Static verification: passed" as
"the requested task is complete." That is false when no task-specific verifier
ran or when the verifier only checked that a target file exists and is readable.

This undermines the central truthfulness goal of `TaskOutcome`.

## Goal

Separate file-level mutation verification from task-completion verification in
both internal outcome status and user-visible wording.

## Scope

### In scope

- Change wording for readback-only verification.
- Introduce or use outcome status that distinguishes:
  - file/readback passed,
  - task-specific verification passed,
  - task-specific verification failed,
  - task completion not verified.
- Prevent "Static verification: passed" wording when no task-specific verifier
  was applicable.
- Add tests for final answer text.

### Out of scope

- Implementing every task-specific verifier.
- Browser execution.
- Runtime JS execution.

## Proposed Work

1. Update `TaskVerificationResult` and/or `ExecutionOutcome` rendering so
   readback-only success is worded as:

   ```text
   File write/readback passed. No task-specific verifier was applicable, so
   task completion was not verified.
   ```

2. Reserve "task verified" or "static verification passed" language for cases
   where task-specific checks actually ran.
3. Ensure partial mutations remain clearly partial.
4. Add assertions in unit/E2E tests against misleading wording.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`

## Test / Verification Plan

- Focused verification rendering tests.
- E2E scenario where a valid file write has no task verifier.
- E2E scenario where a task-specific verifier fails.
- Confirm final answers do not overclaim completion.

## Current Code Read

- `src/main/java/dev/talos/runtime/verification/TaskVerificationStatus.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Planned Tests

- Update the existing non-web readback-only execution-outcome test to require
  non-overclaiming wording and `COMPLETED_UNVERIFIED` outcome status.
- Update the narrow verifier test to distinguish readback-only verification
  from task-specific `PASSED`.
- Add or adjust e2e coverage so a readback-only mutation final answer does not
  contain `Static verification: passed`.

## Acceptance Criteria

- Readback-only success does not say "Static verification: passed".
- The final answer clearly says task completion was not verified.
- Task-specific verifier success can still report verification passed.
- Existing partial/failure truth checks remain intact.

## Implementation Summary

- Added `READBACK_ONLY` to `TaskVerificationStatus` and
  `ExecutionOutcome.VerificationStatus`.
- Added `TaskVerificationResult.readbackOnly(...)` and made
  `StaticTaskVerifier` return it when only target/readback checks pass and no
  task-specific verifier applies.
- Updated final-answer rendering so readback-only success says:
  `File write/readback passed. No task-specific verifier was applicable, so
  task completion was not verified.`
- Preserved `Static verification: passed` for task-specific verifier success.
- Kept readback-only mutation outcomes as `COMPLETED_UNVERIFIED`, not
  `COMPLETED_VERIFIED`.
- Updated e2e expectations for the readback-only create-file retry scenario.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.postApplyNonWebTargetOnlyReadbackDoesNotClaimTaskVerified" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.nonWebMutationUsesNarrowTargetReadbackWording"` -> FAIL at compile because `READBACK_ONLY` did not exist.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.postApplyNonWebTargetOnlyReadbackDoesNotClaimTaskVerified" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.nonWebMutationUsesNarrowTargetReadbackWording"` -> PASS.
- `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest"` -> PASS.
- `./gradlew.bat e2eTest` -> initially failed on scenario 35 expecting old `Static verification: passed` wording; assertion updated to the new readback-only wording.
- `./gradlew.bat e2eTest` -> PASS.
- `./gradlew.bat check` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket changed final-answer truthfulness and outcome
classification, so focused unit tests, full e2e, hard gate `check`, and
installed manual Talos verification were run. Candidate loop was not run because
this is one ticket in the T11-T18 batch, not a declared candidate release.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, the prompt, approval `y`, and
`/q` into the installed Talos CLI.

Workspace:
`local/manual-workspaces/T15/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
Create notes.txt with exactly this text: hello readback wording check. Use the file tool and do not just show code.
```

Approval choice:
`y`

Observed tools:
`talos.write_file`

Files changed:
`local/manual-workspaces/T15/notes.txt`

Output file:
`local/manual-testing/T15-output.txt`

Pass/fail:
PASS

Notes:
The installed CLI created `notes.txt`, printed `File write/readback passed`,
stated that task completion was not verified, and did not print
`Static verification: passed`.

## Known Follow-Ups

- T16 should expand task-specific static verification coverage for web app
  completion; T15 only fixes the outcome/wording for cases where no
  task-specific verifier applies.
