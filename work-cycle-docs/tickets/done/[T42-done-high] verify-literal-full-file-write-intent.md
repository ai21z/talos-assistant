# [T42-done-high] Ticket: Verify Literal Full-File Write Intent
Date: 2026-04-29
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/06-bounded-repair-controller.md`
- `work-cycle-docs/tickets/done/[T40-done-high] mutation-request-with-format-negation-misclassified-read-only.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Why This Ticket Exists

T41 manual live-prompt testing showed Talos correctly classified exact
full-file overwrite prompts as mutation-capable, exposed write tools, required
approval, and created checkpoints. However, qwen wrote different content than
the user requested, and Talos only reported file write/readback success.

Observed prompts:

```text
Overwrite index.html with exactly AFTER. Use talos.write_file.
```

```text
Use talos.write_file to overwrite index.html. Set the content argument to the
exact five letters AFTER. Do not use angle brackets. Do not use placeholders.
The entire file should be AFTER.
```

In both cases the final `index.html` was an HTML page, not the literal
`AFTER`.

## Problem

Readback verification proves the tool wrote the model-provided payload, but it
does not prove the payload matches clear literal-content constraints in the
user request.

## Goal

For narrow literal full-file write requests, Talos should statically verify
that the final file content matches the requested literal content or report the
task as incomplete.

## Scope

In scope:
- Detect clear, narrow literal full-file overwrite constraints.
- Verify final file content against the requested literal content.
- Keep this deterministic and bounded.
- Preserve approval and checkpoint behavior.

Out of scope:
- General natural-language semantic diff verification.
- Browser execution.
- LLM-based verifier.

## Proposed Work

- Add a narrow literal-content extraction policy for patterns such as:
  - `with exactly AFTER`
  - `content argument to the exact five letters AFTER`
  - `The entire file should be AFTER`
- Attach the literal expectation to task verification when a target file is
  explicitly named.
- Fail or downgrade the outcome when the target file does not exactly match.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for literal-content extraction.
- Static verifier tests for matching and mismatching exact content.
- E2E scenario reproducing the T41 prompt shape.
- Manual installed Talos check with qwen if feasible.

## Acceptance Criteria

- Exact full-file overwrite prompts remain mutation-capable.
- If the file content is exactly the requested literal, verification passes.
- If the model writes different content, Talos does not imply the task is done.
- Final answer distinguishes write/readback from requested-content match.
- Existing readback-only wording remains truthful for non-literal tasks.

## Current Code Read

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationStatus.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`
- `src/main/java/dev/talos/runtime/task/TaskContract.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Planned Work-Test Cycle

Inner dev loop only. This ticket does not declare a versioned candidate and
does not update `CHANGELOG.md`.

Focused tests first:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

Then e2e/check/manual installed Talos verification.

## Implementation Summary

- Added a narrow deterministic expectation layer in
  `dev.talos.runtime.expectation`.
- Added `LiteralContentExpectation` and `TaskExpectationResolver` for explicit
  whole-file exact-content requests with one named target.
- Integrated literal expectations into `StaticTaskVerifier`.
- Exact literal matches now produce `PASSED`; exact literal mismatches produce
  `FAILED` and do not degrade to `READBACK_ONLY`.
- Added redacted local-trace expectation events with hashes/counts/status, not
  raw literal content.
- Added deterministic e2e scenarios for exact literal mismatch and exact
  literal match.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

```powershell
./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.literalFullFileWriteMismatchFailsVerification" --tests "dev.talos.harness.JsonScenarioPackTest.literalFullFileWriteMatchPassesVerification" --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat e2eTest --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat check --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat qodanaNativeFreshLocal --no-daemon
./gradlew.bat talosQualitySummaries --no-daemon
```

Result: PASS. Fresh Qodana summary reports `totalIssues=0`,
`highIssues=0`, `criticalIssues=0`.

## Manual Talos Check Result

Command:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Workspace:
`local/manual-workspaces/T42/`

Model:
`qwen2.5-coder:14b`

Prompts:

```text
Overwrite index.html with exactly AFTER. Use talos.write_file.
```

```text
Use talos.write_file to overwrite index.html. Set the content argument to the
exact five letters AFTER. Do not use angle brackets. Do not use placeholders.
The entire file should be AFTER.
```

```text
Make index.html into a simple webpage that says AFTER.
```

Approval choice:
`y` for mutation prompts when approval appeared.

Observed tools:
Cases A/B used `talos.write_file`; Case C used `talos.read_file` and attempted
`talos.write_file`, which was blocked by read-only task policy.

Files changed:
Cases A/B changed `index.html` to literal `AFTER`; Case C left `index.html`
unchanged.

Output file:
`local/manual-testing/T42-output.txt`

Pass/fail:
PASS for T42. Cases A/B verified exact literal content and recorded checkpoint
IDs in `/last trace`. Case C did not create a literal full-file expectation; it
also exposed an adjacent natural-mutation phrasing weakness, but that is outside
this ticket's exact-content verification scope.

Notes:
The live model complied with the literal requests and wrote exactly `AFTER`.
The deterministic e2e mismatch scenario covers the failure mode where the model
writes an HTML document instead of the requested literal.

## Known Follow-Ups

- T43 and T44 remain open and were not implemented in this ticket.
- The negative-control live prompt `Make index.html into a simple webpage that
  says AFTER.` remained read-only. This confirms T42 does not over-detect a
  literal full-file expectation, but the phrasing may deserve a future
  mutation-intent follow-up if the owner wants that natural wording to mutate.

## Commit

Planned commit message:

```text
T42: verify literal full-file write intent
```
