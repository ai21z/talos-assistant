# [T466-done-high] Extract Edit-File Pre-Approval Guard

## Status

Done.

## Scope

T466 extracts edit-file retry pre-approval decision logic from
`ToolCallExecutionStage` into `EditFilePreApprovalGuard`.

This is a behavior-preserving execution-lane extraction. It does not change
tool execution, approval behavior, source-evidence behavior, append-line
behavior, protected/private read handoff, context ledger capture, mutation
evidence, post-result static-web full rewrite detection, prompt repair
planning, final answer wording, or failure policy.

## Source Shape

Before T466, `ToolCallExecutionStage` directly owned adjacent pre-approval
edit retry decisions:

- static-web/full-rewrite repair targets rejecting `talos.edit_file`;
- stale same-file edit failures requiring a later `talos.read_file`;
- duplicate failed `talos.edit_file` suppression;
- repeated empty or missing edit-argument diagnostics.

After T466, `ToolCallExecutionStage` delegates the decision:

```text
EditFilePreApprovalGuard.decision(
    ToolCall call,
    LoopState state,
    String pathHint,
    boolean strict,
    Set<String> staleRereadRequiredAtStart,
    Set<String> fullRewriteRepairTargets
)
```

The guard returns a decision record with:

- decision kind;
- exact diagnostic text;
- normalized path;
- empty-edit flag;
- duplicate call signature.

The stage keeps execution lifecycle side effects:

- failure counters;
- retry counters;
- `cushionFiresB3EditShortCircuit`;
- `recordFailure(...)`;
- `state.staleEditRereadIgnoredPath`;
- `recordEmptyEditArgumentFailure(...)`;
- failed `ToolOutcome` creation;
- result-message append;
- loop `continue`.

## Guardrails Preserved

T466 preserves:

- exact full-rewrite diagnostic wording:
  `Static verification repair requires a complete talos.write_file replacement...`;
- exact stale-reread diagnostic wording:
  `A previous edit changed ... then another edit for the same file failed...`;
- exact duplicate failed edit diagnostic wording:
  `This exact edit was already attempted and failed...`;
- exact repeated empty-edit diagnostic wording;
- strict-mode bypass behavior;
- `talos.edit_file`-only behavior;
- no approval request for blocked retries;
- no mutation for blocked retries;
- stale reread ignored-path behavior;
- empty-edit failure counting;
- failure-policy dominance after repeated empty edits;
- static-web full rewrite continuation behavior.

T466 deliberately does not touch:

- `SourceDerivedEvidenceGuard`;
- `AppendLinePreApprovalGuard`;
- protected/private content handoff;
- mutation evidence;
- context ledger capture;
- post-result static-web full rewrite detection;
- `ToolCallRepromptStage`;
- final answer wording.

## Tests

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFilePreApprovalGuardTest" --no-daemon
```

Failed before implementation because `EditFilePreApprovalGuard` did not exist.

GREEN focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFilePreApprovalGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.repeatedEmptyEditArgsAfterReadStopsWithoutApprovalOrMutation" --tests "dev.talos.runtime.ToolCallLoopTest.emptyEditArgsCanRecoverToValidEditApprovalAfterRead" --tests "dev.talos.runtime.ToolCallLoopTest.repeatedEmptyEditArgsAcrossPathsStopsAfterReadBeforeGenericThreshold" --tests "dev.talos.runtime.ToolCallLoopTest.staleSameFileEditFailureRequiresRereadBeforeNextEdit" --tests "dev.talos.runtime.ToolCallLoopTest.staleSameFileEditCanRecoverAfterSeparateRead" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebOldStringFailureAfterReadRecoversThroughFullWriteReplacement" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsRepeatedEditContinuationBeforeSuccessProse" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.emptyEditRepairIsAvailableOnlyAfterTargetWasReadAndOnlyOnce" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.emptyEditArgsRecoverAfterRead" --tests "dev.talos.harness.JsonScenarioPackTest.staleEditRetryRequiresReread" --tests "dev.talos.harness.JsonScenarioPackTest.emptyEditArgsAcrossPathsStop" --no-daemon
```

Passed after implementation.

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before PR.
