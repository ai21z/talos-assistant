# [T472-done-high] Post-T471 Tool-Call Execution Boundary Decision

## Status

Done.

## Scope

T472 inspects the post-T471 `ToolCallExecutionStage` shape and decides the
next implementation boundary. This is a no-code decision ticket.

It does not change runtime behavior, approval behavior, tool execution,
protected/private handoff, context-ledger capture, mutation/read accounting,
trace wording, prompt wording, outcome wording, or final answer rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `dd00353f`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 748 lines |
| Architecture baseline | 0 |

## Source Evidence

T471 successfully moved the protected/private model-context handoff decision to
`ToolResultModelContextHandoff`. The stage now delegates that decision and keeps
the lifecycle side effects:

```text
ToolResult rawResult = turnProcessor.executeTool(...)
ToolResultModelContextHandoff.decide(...)
state.contentWithheldFromModelContext = true when requested
ContextLedgerCapture.record(...)
emitToolResult(...)
```

The remaining execution-stage responsibilities are:

| Responsibility | Current source | Decision |
|---|---|---|
| Protected alias normalization | `ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(...)` before path planning | Keep local for now. It is task-contract and protected-path policy behavior, not a small post-result cleanup. |
| Tool path/plan derivation | `workspaceOperationPlan(...)` and `pathHint(...)` at the top of each tool execution, repeated after source-evidence write repair | Coherent next extraction. It owns derived path metadata for progress, guards, tool outcomes, and repair evidence. |
| Pre-approval guard dispatch | `EditFilePreApprovalGuard`, `RedundantReadSuppressionGuard`, `SourceDerivedEvidenceGuard`, `AppendLinePreApprovalGuard` calls | Already split enough for now. The stage is still the ordering owner. |
| Model-context handoff | `ToolResultModelContextHandoff.decide(...)` | Closed for this lane. Do not move ledger recording into the owner yet. |
| Context ledger side effect | `recordContextLedgerDecision(...)` | Keep in the stage for now. It is explicit, tiny, and tied to lifecycle accounting. Moving it now would hide a global side effect for little architectural gain. |
| Read/mutation accounting | `recordSuccessfulRead(...)`, `recordMutationSuccess(...)`, `successfulReadCalls`, mutation summaries, clear read cache | Not the next ticket. This is broader state mutation and needs its own decision if attacked. |
| Failure classification and recovery | denial/path-policy flags, unsupported-read list, stale-edit accounting, static-web full-rewrite recovery | Not a small move. It mixes outcome dominance, repair policy, task contracts, and static-web behavior. |

## Decision

Do not extract another random piece from `ToolCallExecutionStage`.

The next correct implementation ticket is:

```text
[T473] Extract tool execution path context
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolExecutionPathContext
```

Preferred shape:

```text
record ToolExecutionPathContext(
    WorkspaceOperationPlan workspaceOperationPlan,
    String pathHint
) {
    static ToolExecutionPathContext from(ToolCall call)
}
```

The owner should:

- call `WorkspaceOperationPlanner.checkpointPlan(...)` only for workspace
  operation tools;
- preserve the current fail-soft behavior when `checkpointPlan(...)` throws
  `IllegalArgumentException`;
- use `WorkspaceOperationPlan.primaryChangedPath()` when present;
- fall back to `ToolCallSupport.resolvePathHint(call)` otherwise.

`ToolCallExecutionStage` should keep:

- the timing of when path context is derived;
- re-deriving path context after `SourceDerivedEvidenceGuard` repairs a write;
- progress/log emission;
- passing `workspaceOperationPlan` into `ToolOutcome`;
- all read/mutation accounting and failure policy.

## Why This Is The Correct Next Slice

The current path/plan derivation is a coherent derived-data owner. It is used by
nearly every downstream stage decision, but the derivation itself is pure,
small, and locally testable.

Moving it improves ownership without changing high-risk behavior. It also
removes direct `WorkspaceOperationPlanner` knowledge from the execution loop
while keeping the loop responsible for execution ordering.

## Rejected Immediate Work

### Move context ledger recording into `ToolResultModelContextHandoff`

Rejected for T473.

After T471, the stage ledger method is explicit and tiny. Moving it now hides a
global side effect inside a policy owner. That may be revisited only if a later
source inspection proves the lifecycle side effect is still a real ownership
problem.

### Extract read/mutation accounting next

Rejected for T473.

That cluster mutates several loop-state collections and counters, affects
repair behavior, and needs a broader state-accounting decision before code
moves.

### Extract static-web full-rewrite recovery

Rejected for T473.

It mixes task contracts, static-web capability classification, trace recording,
and repair context. It is not a cheap continuation of the handoff lane.

### Extract protected alias normalization

Rejected for T473.

It is pre-execution task-contract/protected-path policy. It should wait for a
path-policy pipeline decision, not be moved as incidental cleanup.

## Required T473 Tests

Start with RED tests for `ToolExecutionPathContext`:

- read-only calls return no workspace operation plan and use
  `ToolCallSupport.resolvePathHint(...)`;
- workspace operation calls return a plan and prefer
  `WorkspaceOperationPlan.primaryChangedPath()`;
- invalid workspace-operation arguments preserve the current fail-soft fallback
  to `ToolCallSupport.resolvePathHint(...)`;
- `ToolCallExecutionStage` delegates path/plan derivation to
  `ToolExecutionPathContext` and no longer imports `WorkspaceOperationPlanner`.

Focused checks should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionPathContextTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.WorkspaceOperationTurnProcessorTest" --tests "dev.talos.runtime.WorkspaceBatchTurnProcessorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceDerivedEvidenceGuardTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
```

Then run the normal gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
