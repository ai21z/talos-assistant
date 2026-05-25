# [T473-done-high] Extract Tool Execution Path Context

## Status

Done.

## Scope

T473 implements the T472 decision by extracting derived tool path/plan metadata
from `ToolCallExecutionStage` into:

```text
dev.talos.runtime.toolcall.ToolExecutionPathContext
```

This is an ownership refactor. It preserves behavior, prompt/result wording,
approval behavior, checkpoint planning semantics, trace behavior, failure
classification, repair behavior, and final answer rendering.

## What Moved

`ToolExecutionPathContext` now owns:

- deriving `WorkspaceOperationPlan` for workspace-operation tools;
- fail-soft fallback to no plan when `WorkspaceOperationPlanner.checkpointPlan`
  throws `IllegalArgumentException`;
- choosing `WorkspaceOperationPlan.primaryChangedPath()` as the preferred
  `pathHint` when available;
- falling back to `ToolCallSupport.resolvePathHint(...)` otherwise.

`ToolCallExecutionStage` still owns:

- when path context is derived;
- re-deriving path context after `SourceDerivedEvidenceGuard` repairs a write;
- progress/log emission;
- pre-approval guard ordering;
- passing `WorkspaceOperationPlan` into `ToolOutcome`;
- protected/private model-context handoff;
- context-ledger recording;
- read/mutation accounting;
- failure and repair policy.

## Guardrails Preserved

T473 does not move:

- protected alias normalization;
- source-derived evidence policy;
- append-line or edit pre-approval guards;
- protected/private handoff;
- context-ledger side effects;
- read/mutation state accounting;
- static-web full rewrite recovery.

## Test Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionPathContextTest" --no-daemon
```

Failed because `ToolExecutionPathContext` did not exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionPathContextTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.WorkspaceOperationTurnProcessorTest" --tests "dev.talos.runtime.WorkspaceBatchTurnProcessorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceDerivedEvidenceGuardTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
```

All focused checks passed locally.

## Next Move

After T473 is merged, inspect the remaining `ToolCallExecutionStage` shape
again. The likely next area is read/mutation state accounting, but it should
start with inspection or a short decision ticket because it mutates several
loop-state collections and affects repair behavior.
