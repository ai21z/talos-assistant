# T98 - Multi-File Web Create Continues Until Expected Targets

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: Focused prompt-construction re-audit follow-up

## Evidence Summary

- Audit root: `local/manual-testing/prompt-construction-focused-reaudit-20260503-103426`
- Finding: exact and expected-target prompt construction reaches the provider body, but multi-file BMI creation can still stop after mutating only part of the expected target set.
- Qwen evidence: BMI create ended `Outcome: FAILED (FAILED)` after not successfully mutating `index.html`.
- GPT-OSS evidence: BMI create ended `Outcome: FAILED (FAILED)` after not successfully mutating `styles.css` and `scripts.js`.

## Problem

The P0 tool-loop optimization stops after a clean successful mutation iteration. That is correct for single-target edits, but too early for current-turn tasks with multiple expected file targets. The runtime should continue the same tool loop when expected targets remain unmutated.

## Scope

- Keep the P0 skip for completed mutation sets.
- If a mutation-capable turn has expected targets and an all-success iteration mutates only some of them, reprompt with a bounded progress instruction naming the remaining exact paths.
- Preserve static verification and failure-dominant output if the model still fails.
- Do not add a deterministic web app generator.

## Acceptance Criteria

- Regression proves a three-file web create does not stop after only `index.html`.
- Runtime continues to `styles.css` and `scripts.js` in the same turn.
- Final static verification can pass after all expected targets are mutated.
- Existing structural repair scenarios still pass.

## Resolution

- Added an e2e scenario proving a three-file static BMI create continues after the first successful file write.
- Changed the P0 all-success mutation shortcut to continue when the latest user request explicitly names expected targets that have not been successfully mutated in the current turn.
- Added a bounded expected-target progress prompt that names the remaining exact paths and rejects similar filenames as substitutes.
- Scoped expected-target continuation to current-turn explicit targets so vague repair follow-ups do not re-open historical target sets.

## Verification

- `.\gradlew.bat e2eTest --tests dev.talos.harness.JsonScenarioPackTest.repairFollowupAfterIncompleteOutcomeApplies --no-daemon`
- `.\gradlew.bat e2eTest --tests dev.talos.harness.JsonScenarioPackTest.multiFileWebCreateContinuesUntilExpectedTargets --no-daemon`
- `.\gradlew.bat e2eTest --tests dev.talos.harness.JsonScenarioPackTest.structuralWebRepairContinuesUntilPlannedWriteTargets --tests dev.talos.harness.JsonScenarioPackTest.structuralWebRepairRedirectsEditFileToWriteFile --tests dev.talos.harness.JsonScenarioPackTest.overwriteRepairPhrasingAllowsMutation --tests dev.talos.harness.JsonScenarioPackTest.functionalWebTaskMissingJavascriptFailsVerification --tests dev.talos.harness.JsonScenarioPackTest.repairFollowupAfterIncompleteOutcomeApplies --tests dev.talos.harness.JsonScenarioPackTest.multiFileWebCreateContinuesUntilExpectedTargets --no-daemon`
- `.\gradlew.bat clean test e2eTest installDist --no-daemon`
