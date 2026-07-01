# T701 - Static-Web Status Answers Use Last Verification State

Status: done
Severity: high

## Problem

In T698, Qwen answered a status-only prompt after failed static-web verification with:

```text
The static verification indicates that the required content and structure are present in the files.
```

That contradicted the latest verifier state. The previous static-web turn had failed, and the status-only turn did not run post-apply verification.

## Evidence

- Audit root:
  `local/TalosTestOUTPUT/test02-11-post-t697-t698-sync-audit-20260606-131440/`
- Qwen fresh transcript:
  - P1/P2 verification failed.
  - P4 prompt: `Is it verified now? What, if anything, is still unverified?`
  - P4 trace: `READ_ONLY_QA`, `Verification: NOT_RUN`, `Outcome: READ_ONLY_ANSWERED`.
  - P4 assistant preview overclaimed static verification success.
- Final Qwen workspace still had unresolved static-web concerns:
  - remote Tailwind CSS href not accepted as Tailwind runtime/build proof,
  - exact required phrase drift still flagged by content preservation.

## Architecture Metadata

- Capability ownership: status/read-only outcome rendering / verification-state memory.
- Operation type: read-only status/explanation turn after a prior mutation/verification turn.
- Risk: high. Users ask status prompts to decide whether to trust the result.
- Approval behavior: no mutation tools should be exposed.
- Protected path behavior: unchanged.
- Checkpoint behavior: unchanged.
- Evidence obligation: status answer must be grounded in latest available verification/trace state or state that no current verifier state is available.
- Verification profile: status turns do not run post-apply verification unless a dedicated verify-only path exists.
- Repair profile: none.
- Outcome/trace changes: status answer should surface previous failed/unverified state without pretending a new verification ran.
- Allowed refactor scope: outcome rendering, session turn lookup, status/read-only answer guards, trace rendering tests.

## Acceptance

- After a static-web turn with `Verification: FAILED`, a follow-up `Is it verified now?` must answer from the latest stored verifier state.
- It must not say static verification indicates success unless the latest verifier state actually passed.
- If no latest verifier state is available in the current process/session, it must say that explicitly and may inspect files, but must not infer verified status from file reads alone.
- Status-only prompts remain read-only and expose no mutation tools.
- Explanation-only prompts can cite the latest verifier problems and inspected files.

## Tests

- Added `AssistantTurnExecutorTest.verificationStatusQuestionUsesLatestRuntimeVerifierFailureNotModelOverclaim`.
  It seeds failed runtime verifier state, scripts an LLM success overclaim, and verifies the final answer is runtime-owned.
- Added `AssistantTurnExecutorTest.verificationStatusQuestionWithoutLoadedVerifierStateDoesNotInferSuccess`.
  It verifies a direct status question with no loaded verifier state says no prior verifier state is available instead of inferring success.
- Focused commands:
  - `.\gradlew.bat test --tests "*verificationStatusQuestionUsesLatestRuntimeVerifierFailureNotModelOverclaim" --tests "*verificationStatusQuestionWithoutLoadedVerifierStateDoesNotInferSuccess" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.runtime.task.WorkspaceTargetReconcilerTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.toolcall.StaticWebRepairPathGuardTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon`
  - `.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.structuralWebRepairContinuesUntilPlannedWriteTargets" --tests "dev.talos.harness.JsonScenarioPackTest.scopedTargetLimiterBlocksForbiddenTarget" --tests "dev.talos.harness.JsonScenarioPackTest.emptyEditArgsAcrossPathsStop" --no-daemon`
  - `.\gradlew.bat check --no-daemon`

## Non-Goals

- Do not make every status prompt trigger a full verifier run.
- Do not load prior sessions implicitly.
- Do not add visual/render verification.
