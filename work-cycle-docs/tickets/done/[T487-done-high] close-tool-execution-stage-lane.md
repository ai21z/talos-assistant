# [T487-done-high] Close Tool Execution Stage Lane

## Status

Done.

## Scope

T487 inspects the post-T486 `ToolCallExecutionStage` shape and decides whether
the current execution-stage extraction lane should continue.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected-read behavior, tool execution, handoff behavior,
trace wording, prompt wording, outcome wording, or final-answer behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `45861dd9`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 493 lines |
| `ToolCallRepromptStage.java` | 1007 lines |
| Architecture baseline | 0 |

## Source Evidence

The completed execution-stage lane extracted the clearly separable owners:

- tool execution path context;
- successful read evidence accounting;
- mutation evidence construction;
- mutation state accounting;
- failed-tool classification;
- generic failure state accounting;
- edit-failure repair state accounting;
- failed-tool iteration signals;
- `ToolCallLoop.ToolOutcome` construction.

After T486, `ToolCallExecutionStage` still coordinates these pre-execution
blocks:

- `EditFilePreApprovalGuard` decision handling;
- `RedundantReadSuppressionGuard` handling;
- `SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(...)`;
- `SourceDerivedEvidenceGuard.exactEvidenceCoverageDiagnostic(...)`;
- `AppendLinePreApprovalGuard.diagnostic(...)`.

Those remaining blocks are not cheap mechanical extractions. They mix:

- guard policy decisions;
- failure accounting;
- synthetic `ToolResult` creation;
- trace/action-obligation records;
- optional source-evidence repair;
- tool-result formatting;
- logging;
- loop continuation control.

Extracting one of those blocks just to reduce line count would hide policy
behavior inside another procedural owner without clarifying the architecture.

## Decision

Close the current `ToolCallExecutionStage` extraction lane for now.

`ToolCallExecutionStage` is not tiny, but it is now mostly a readable execution
orchestrator. The remaining pre-execution block handling should be revisited
only after a targeted policy-boundary decision, not as another automatic
burn-down.

## Next Correct Lane

Start the next ticket as an inspection/decision ticket for
`ToolCallRepromptStage`, not an implementation ticket.

Recommended next ticket:

```text
[T488] ToolCallRepromptStage Boundary Decision
```

Why:

- `ToolCallRepromptStage.java` is now 1007 lines.
- It owns multiple responsibilities:
  - failure-policy stop handling;
  - terminal read-only answer selection;
  - static-web continuation orchestration;
  - read-only repair budget behavior;
  - compact mutation continuation;
  - source-evidence exact repair continuation;
  - append-line and old-string compact repair continuation;
  - expected-target and static-repair pending obligations;
  - chat reprompt request construction;
  - context-budget overflow handling.
- Some of those already delegate to extracted planners, but the stage still
  owns broad orchestration and several private helper clusters.

T488 should inspect whether the next coherent implementation unit is:

- context-budget overflow continuation handling;
- failure-policy stop message/rendering;
- chat-reprompt request construction;
- pending action-obligation selection;
- or a no-code closeout/retarget decision.

## Rejected Immediate Work

### Extract Source-Derived Pre-Execution Block From `ToolCallExecutionStage`

Rejected for now.

The source-derived block combines policy diagnostics, optional repair, trace
records, synthetic failure results, outcome recording, formatting, and logging.
That is a design boundary, not a simple helper move.

### Extract Append-Line Pre-Execution Block From `ToolCallExecutionStage`

Rejected for now.

Append-line preservation is a policy guard with action-obligation semantics.
Moving the block without first deciding the guard/trace/repair ownership model
would create a procedural dumping ground.

### Extract Redundant Read Handling From `ToolCallExecutionStage`

Rejected for now.

It is small and readable in place. It does not justify a new owner compared
with the much larger reprompt-stage hotspot.

## Verification

No code changed.

Required gates:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
