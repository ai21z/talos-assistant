# T152 - Static Web Full-Rewrite Repair Must Enforce WriteFile After OldString Miss

Status: done
Priority: high

## Evidence Summary

- Source: full llama.cpp T61-E + product workflow audit
- Date: 2026-05-05
- Model/backend: managed llama.cpp with `gpt-oss:20b`
- Findings report:
  - `local/manual-testing/llama-cpp-t61e-full-audit-20260505-235337/FINDINGS-LLAMA-CPP-T61E-FULL-AUDIT.md`
- Transcript:
  - `local/manual-testing/llama-cpp-t61e-full-audit-20260505-235337/TEST-OUTPUT-LLAMA-CPP-PRODUCT-WORKFLOW-GPT-OSS-20B.txt`

Prompt:

```text
Fix the static web button fixture. The existing index.html loads script.js; the button with id run-button should set #result to Clicked. Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.
```

Observed:

- Line 2416 sends the prompt.
- Line 2457 reports `old_string not found in script.js`.
- Line 2459 says static verification repair requires a complete `talos.write_file` replacement for `script.js`.
- Line 2532 records `Outcome: FAILED (FAILED)`.
- Final workspace `script.js` still uses `.missing-button`.

## Problem

T151 improved the focused static web repair path, but the broader product workflow still found a GPT-OSS failure. The runtime detects that `edit_file` failed after read evidence and says a complete `write_file` replacement is required, but it still lets the model continue through a probabilistic read/edit loop instead of enforcing the write-file repair transition.

This is not a wording problem. It is a repair-control problem.

## Goal

When static web repair requires a complete rewrite for a small target after an old-string miss, Talos must either:

- execute a valid `talos.write_file` replacement for the target, or
- fail once with a deterministic typed repair breach.

It must not consume the loop budget on repeated read-only or invalid edit attempts after the rewrite requirement is known.

## Scope

In scope:

- Track static-web full-rewrite-required targets after `old_string not found` following fresh read evidence.
- Enforce the next repair transition for those targets.
- Allow `talos.write_file` for the required target.
- Treat repeated read-only, wrong-target, or `edit_file` attempts for that target as deterministic repair breach after a bounded attempt.
- Preserve failure-dominant output.
- Preserve successful valid `edit_file` paths where full rewrite is not required.

Out of scope:

- No broad prompt-wording rewrite.
- No new model classifier.
- No shell/browser verification.
- No global forced-tool abstraction.

## Acceptance Criteria

- GPT-OSS-shaped failure is covered: read `index.html`, read `script.js`, invalid `edit_file` old-string miss, then model tries read/edit again instead of `write_file`; Talos does not hit iteration limit and records a typed repair breach or enforces the complete write.
- A valid `talos.write_file` replacement for `script.js` completes and static verification passes.
- Existing Qwen-shaped valid static repair still passes.
- Failure output names the target and repair requirement and contains no success/manual-save prose.
- Trace records the ordered control state: old-string miss after read evidence, full-rewrite requirement raised, enforcement attempted, repair completed or breach final.
- No regression to expected-target checking, protected reads, approval, or changed-files summary ownership.

## Tests

Required tests:

- Unit/tool-loop test for full-rewrite-required target after old-string miss.
- Integration/executor test for static web button repair where the model repeats invalid edit/read attempts after the rewrite requirement is known.
- Happy-path test where the model emits `talos.write_file` for `script.js` and verification passes.
- Failure-dominance test for deterministic repair breach.
- Trace sequence assertion for the repair-control state.

Suggested verification commands:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest
.\gradlew.bat --no-daemon e2eTest --tests dev.talos.harness.JsonScenarioPackTest.structuralWebRepairRedirectsEditFileToWriteFile
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon e2eTest
.\gradlew.bat --no-daemon check
.\gradlew.bat --no-daemon installDist
```

## Manual Audit

After implementation:

- Run a focused static web repair audit with Qwen and GPT-OSS.
- Then rerun the broader product workflow before another full T61-style audit.

Expected manual result:

- GPT-OSS no longer leaves `script.js` with `.missing-button`.
- The turn either verifies cleanly or fails with a deterministic repair-control breach before loop exhaustion.

## Closeout Evidence

Implementation summary:

- Dynamic static-web full-rewrite targets now activate a pending static repair obligation as soon as an old-string miss is recorded after read evidence.
- While that static repair obligation is pending, the next model response must include `talos.write_file` for one of the remaining repair targets.
- Read-only, wrong-tool, or `talos.edit_file` continuations under that obligation fail deterministically before additional tools execute.
- Direct `talos.write_file` recovery remains allowed and satisfies the obligation.

Regression coverage:

- Added `ToolCallLoopTest.staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse`.
- Added `ToolCallLoopTest.staticWebFullRewriteRequiredRejectsRepeatedEditContinuationBeforeSuccessProse`.
- Updated the existing static-web old-string recovery test so the successful path is now direct `write_file` after the rewrite obligation is raised.

Verification:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsRepeatedEditContinuationBeforeSuccessProse --tests dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse --tests dev.talos.runtime.ToolCallLoopTest.staticWebOldStringFailureAfterReadRecoversThroughFullWriteReplacement
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest.staticWebVerifierPassStopsWithoutExpectedContextTargetBreach --tests dev.talos.runtime.ToolCallLoopTest.staticWebOldStringFailureAfterReadRecoversThroughFullWriteReplacement --tests dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse --tests dev.talos.runtime.ToolCallLoopTest.staticRepairProgressNoToolProseBecomesDeterministicBreach --tests dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressNoToolProseBecomesDeterministicBreach --tests dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressToolCallKeepsHappyPathOpen
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest --tests dev.talos.runtime.outcome.MutationOutcomeTest --tests dev.talos.runtime.verification.StaticTaskVerifierTest
.\gradlew.bat --no-daemon e2eTest --tests dev.talos.harness.JsonScenarioPackTest.structuralWebRepairRedirectsEditFileToWriteFile --tests dev.talos.harness.JsonScenarioPackTest.structuralWebRepairContinuesUntilPlannedWriteTargets --tests dev.talos.harness.JsonScenarioPackTest.repairAfterStaticVerificationFailureUsesVerifierContext --tests dev.talos.harness.JsonScenarioPackTest.repairFollowupAfterIncompleteOutcomeApplies
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon e2eTest
.\gradlew.bat --no-daemon check
.\gradlew.bat --no-daemon installDist
```

Manual audit:

- `local/manual-testing/t152-static-web-full-rewrite-gate-audit-20260506-051126/FINDINGS-T152-STATIC-WEB-FULL-REWRITE-GATE-AUDIT.md`

Manual audit result:

- GPT-OSS confirmed the T152 control fix: after the old-string miss path, the model attempted `talos.read_file(script.js)` under a pending static repair obligation and Talos stopped deterministically with `STATIC_REPAIR_TARGETS_REMAINING` instead of looping to the iteration limit.
- Qwen exposed a separate verifier bug: it wrote broken JavaScript with `.textC;`, and static verification incorrectly passed. Tracked separately as T156.

Known follow-up:

- T156 - Static Web Verifier Must Reject Broken JS Handler Mutations.
