# [T465-done-high] ToolCallExecutionStage Edit Guard Boundary Decision

## Status

Done.

## Scope

T465 inspects the post-T464 `ToolCallExecutionStage` shape after append-line
and source-evidence pre-approval guards were moved to dedicated owners.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected/private read handling, source-evidence behavior,
static-web repair behavior, mutation evidence, context ledger capture, tool
result wording, trace wording, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `fa2f2a0c`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 1074 lines |
| Architecture baseline | 0 |

## Post-T464 Source Shape

The execution stage now delegates these pre-approval source/append decisions:

- append-line full-write preservation to `AppendLinePreApprovalGuard`;
- source-derived write-before-source-read blocking to
  `SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(...)`;
- source-derived exact evidence coverage and deterministic repair to
  `SourceDerivedEvidenceGuard`.

The next dense execution-stage cluster is edit-file retry safety:

1. static-web/full-rewrite repair targets block `talos.edit_file`;
2. stale same-file edit failures require a later `talos.read_file`;
3. duplicate failed `talos.edit_file` calls are suppressed before approval;
4. repeated empty or missing edit arguments are counted for failure policy;
5. exact diagnostics tell the model how to recover without requesting
   approval or mutating files.

These branches are adjacent in the execution pipeline and all run before
`TurnProcessor.executeTool(...)`.

## Decision

Do not extract one isolated static-web diagnostic branch by itself.

The correct next implementation boundary is the edit-file pre-approval retry
guard as one owner:

```text
[T466] Extract edit-file pre-approval guard
```

Target owner:

```text
dev.talos.runtime.toolcall.EditFilePreApprovalGuard
```

Preferred shape:

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

The owner should return a decision record, not mutate `LoopState`:

```text
Decision(
    Kind kind,
    String diagnostic,
    String normalizedPath,
    boolean emptyEditArguments,
    String callSignature
)
```

Suggested decision kinds:

- `FULL_REWRITE_REPAIR_REQUIRED`;
- `STALE_REREAD_REQUIRED`;
- `DUPLICATE_FAILED_EDIT`;
- `NONE`.

`ToolCallExecutionStage` should keep lifecycle and side effects:

- incrementing `failedCalls`;
- incrementing `failuresThisIter`;
- incrementing `retriedCalls`;
- incrementing `cushionFiresB3EditShortCircuit`;
- calling `recordFailure(...)`;
- assigning `state.staleEditRereadIgnoredPath`;
- calling `recordEmptyEditArgumentFailure(...)`;
- creating the failed `ToolOutcome`;
- appending the tool-result message;
- deciding `continue`.

## Why This Boundary

This is one coherent behavior owner because all selected cases answer the same
question:

```text
Should this talos.edit_file retry be blocked before approval because the
current loop state proves it is the wrong recovery action?
```

Extracting only the static-web full-rewrite branch would leave the adjacent
stale-read and duplicate-edit diagnostics in `ToolCallExecutionStage`, which
keeps the ownership confusion intact.

Extracting more than this would be too broad. The post-result static-web
recovery detector, mutation-evidence extraction, protected/private content
handoff, context ledger capture, and read/mutation state accounting are
different ownership lanes.

## Guardrails For T466

T466 must preserve:

- exact full-rewrite diagnostic wording:
  `Static verification repair requires a complete talos.write_file replacement...`;
- exact stale reread diagnostic wording:
  `A previous edit changed ... then another edit for the same file failed...`;
- exact duplicate failed edit diagnostic wording:
  `This exact edit was already attempted and failed...`;
- exact repeated empty-edit diagnostic wording;
- strict-mode bypass behavior;
- `talos.edit_file` only, not `write_file` or read-only tools;
- no approval request for blocked retries;
- no mutation for blocked retries;
- stale reread ignored-path behavior;
- empty-edit failure counting;
- failure-policy dominance after repeated empty edits;
- static-web full rewrite continuation behavior.

T466 must not touch:

- `SourceDerivedEvidenceGuard`;
- `AppendLinePreApprovalGuard`;
- protected/private document model handoff;
- context ledger capture;
- mutation evidence;
- post-result static-web full rewrite detection;
- `ToolCallRepromptStage`;
- final answer wording.

## Proposed T466 Tests

Start with RED ownership tests for `EditFilePreApprovalGuard`:

```text
EditFilePreApprovalGuardTest
```

It should prove:

- full-rewrite targets return the exact full-rewrite diagnostic;
- stale reread paths return the exact stale-reread diagnostic;
- duplicate failed edit calls return the exact duplicate diagnostic;
- duplicate empty edit calls return the exact empty-edit diagnostic;
- strict mode returns no decision;
- non-`edit_file` calls return no decision;
- `ToolCallExecutionStage` delegates to the guard and no longer owns the
  diagnostic helper methods.

Focused regression checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest*stale*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest*emptyEdit*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest*fullRewrite*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest*29*" --tests "dev.talos.harness.JsonScenarioPackTest*34*" --no-daemon
```

The exact test filters may be adjusted after source inspection, but T466 must
include focused stale-edit, empty-edit, and full-rewrite regressions before
the full gate.

## Rejected Immediate Work

### Broad execution policy pipeline

Rejected. It would mix pre-approval edit retry safety, source evidence,
append-line safety, protected/private content handoff, mutation evidence, and
post-result recovery in one refactor.

### Static-web full rewrite branch only

Rejected for T466. It is smaller but worse ownership: stale reread and
duplicate failed edit guards are adjacent pre-approval retry safety and should
move with the same owner.

### Protected/private handoff

Rejected for this lane. It runs after the tool result exists and includes
approval prompts, model-context containment, content metadata, privacy notes,
and context ledger capture. It needs its own decision ticket.

### Mutation evidence

Rejected for this lane. It is outcome/verifier evidence, not pre-approval edit
retry safety.

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before PR.
