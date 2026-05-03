# T100 - Complete Pending Obligation Outcome And Repair Scope

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: T99 focused clean Qwen/GPT-OSS re-audit

## Evidence

Focused audit:

- `local/manual-testing/t99-focused-clean-audit-20260503-134443/FINDINGS-T99-FOCUSED-TWO-MODEL.md`
- `local/manual-testing/t99-focused-clean-audit-20260503-134443/TEST-OUTPUT-GPT-OSS-20B.txt`
- `local/manual-testing/t99-focused-clean-audit-20260503-134443/TEST-OUTPUT-QWEN-14B.txt`

Observed:

- GPT-OSS triggered the T99 visible pending-obligation failure block.
- `/last trace` still reported the same turns as `Outcome: COMPLETE (COMPLETED_VERIFIED)`.
- A stale `script.js` static repair target remained active during a new BMI task whose current expected JavaScript target was `scripts.js`.
- A later `Review ... and fix ...` prompt could classify as read-only after the breach was recorded as complete.

## Problem

T99 added visible pending-obligation containment, but the breach is not yet a
dominant machine-readable turn outcome. That leaves active task context,
trace summaries, repair scoping, and follow-up classification inconsistent.

## Scope

- Pending action obligation failure must dominate `ExecutionOutcome` and local
  trace classification even when mutating tools already succeeded and static
  files would otherwise verify.
- Static repair full-rewrite targets for structural web repair must be scoped
  to the current turn's explicit expected targets when those targets are known.
  Stale sibling targets like `script.js` must not remain required for a new
  `scripts.js` task.
- `Action obligation failed` assistant output must count as an incomplete
  mutation outcome so natural follow-ups such as `Review ... and fix ...`
  inherit the previous mutation-capable contract.

## Acceptance

- A pending-obligation breach produces `BLOCKED` / `BLOCKED_BY_POLICY` in
  `ExecutionOutcome` and `/last trace`, not `COMPLETE` /
  `COMPLETED_VERIFIED`.
- The breach remains failure-dominant and contains no success/manual-save prose.
- A new explicit BMI task with expected `index.html`, `styles.css`, and
  `scripts.js` does not keep stale `script.js` as a full-rewrite repair target.
- `Review ... and fix ...` after an action-obligation failure inherits the
  previous mutation contract.
- Existing successful verified mutation paths still report
  `COMPLETED_VERIFIED`.

## Implementation Result

- `ExecutionOutcome` now treats stopped pending-action-obligation failures as
  dominant failed mutation obligations before static verification can report a
  completed verified outcome.
- Structural static-web repair planning now uses the current turn's explicit
  expected targets for full-file rewrite repair when those targets are known,
  preventing stale sibling targets from previous failures from leaking into the
  new repair scope.
- Task contract resolution now treats `Action obligation failed` output as an
  incomplete prior mutation outcome, so natural `review and fix` follow-ups can
  inherit the previous mutation-capable contract.
- Scenario 27 now asserts the earlier deterministic pending-target breach
  rather than the older static-verifier failure text while preserving the safety
  assertions that the missing target is not hidden behind success prose.

## Verification

- `./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.pendingActionObligationFailureDominatesVerifiedMutationOutcomeAndTrace" --tests "dev.talos.runtime.repair.RepairPolicyTest.explicitStructuralWebTaskDoesNotCarryStaleSiblingRepairTarget" --tests "dev.talos.runtime.task.TaskContractResolverTest.reviewAndFixAfterActionObligationFailureInheritsExpectedTargets" --no-daemon`
- `./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon`
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.multiFileWebCreateContinuesUntilExpectedTargets" --tests "dev.talos.harness.JsonScenarioPackTest.structuralWebRepairContinuesUntilPlannedWriteTargets" --tests "dev.talos.harness.JsonScenarioPackTest.structuralWebRepairRedirectsEditFileToWriteFile" --no-daemon`
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.staticVerifierMissingScriptDowngradesIncomplete" --no-daemon`
- `./gradlew.bat clean test e2eTest installDist --no-daemon`
