# [T498-done-high] Read-Only Repair Budget Boundary Decision

## Status

Done.

## Scope

T498 inspects the read-only repair and mutation budget logic after the
tool-call reprompt-stage lane was closed by T497.

This is a no-code decision ticket. It does not change runtime behavior,
budget thresholds, repair/fix truthfulness wording, conditional review/fix
handling, compact mutation continuation, trace wording, failure policy, tool
ordering, approval behavior, protected path behavior, or verification behavior.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T497:

| Source | Relevant ownership |
| --- | --- |
| `ToolCallRepromptStage.repairReadOnlyBudgetExceeded(...)` | detects repair/fix turns that exhausted read-only inspection without mutation |
| `ToolCallRepromptStage` lines 167-197 | applies conditional no-change or deterministic `REPAIR_INSPECTION_ONLY` failure |
| `ToolCallRepromptStage.mutationReadOnlyBudgetExceeded(...)` | detects mutation turns that exhausted read-only evidence collection |
| `ToolRepromptContextBudgetHandler.handleReadOnlyMutationEvidenceBudget(...)` | attempts compact mutation continuation after mutation read-only evidence budget |
| `CompactMutationContinuationPlanner` | owns compact mutation prompt/tool/readback construction |
| `ConditionalReviewFixPolicy` | owns evidence-backed no-change closure for conditional review/fix |
| `ResponseObligationVerifier.deterministicRepairInspectionOnlyAnswer()` | owns deterministic repair-inspection-only answer wording |

Relevant tests already exercise the behavior:

- `ToolCallLoopTest` repair/fix read-only budget stops with
  `REPAIR_INSPECTION_ONLY` before the generic loop limit.
- `ToolCallLoopTest` redundant read suppression counts toward the repair budget.
- `ToolCallLoopTest.singleTargetMutationReadOnlyOverInspectionUsesCompactMutationContinuation`
  verifies mutation read-only over-inspection uses compact mutation continuation.
- `ToolRepromptContextBudgetHandlerTest` verifies compact mutation continuation
  success and no-tool failure paths.
- `AssistantTurnExecutorTest` verifies conditional review/fix no-change and
  repair-inspection-only behavior.

## Decision

The repair/fix read-only inspection budget and the mutation read-only evidence
budget must not be extracted together.

They share an attempt counter and threshold, but their ownership is different:

- repair/fix read-only budget is an action-obligation terminal gate;
- mutation read-only evidence budget is a compact mutation continuation gateway.

Bundling them would create a misleading "budget manager" with two unrelated
side effects: deterministic repair failure and compact mutation retry.

## Next Coherent Implementation Slice

The next implementation ticket should be:

```text
[T499] Extract repair inspection budget gate
```

Recommended owner:

```text
dev.talos.runtime.toolcall.ToolRepairInspectionBudgetGate
```

Scope:

- move the repair/fix read-only budget branch out of
  `ToolCallRepromptStage`;
- preserve the existing threshold;
- preserve the existing conditional review/fix no-change fast path;
- preserve the exact `REPAIR_INSPECTION_ONLY` failure reason;
- preserve `ResponseObligationVerifier.deterministicRepairInspectionOnlyAnswer()`;
- preserve action-obligation trace fields:
  - obligation name;
  - `FAILED`;
  - reason;
  - `REPAIR_INSPECTION_ONLY`;
- leave mutation read-only evidence budget and compact mutation continuation
  untouched.

Recommended API:

```java
static Optional<Boolean> tryStop(LoopState state, int readOnlyToolBudget)
```

Return semantics:

- `Optional.empty()` when the repair-inspection budget gate does not apply;
- `Optional.of(false)` when it sets a terminal answer and stops the loop.

`ToolCallRepromptStage` should retain the ordering decision:

```java
Optional<Boolean> repairBudgetStop =
        ToolRepairInspectionBudgetGate.tryStop(state, REPAIR_READ_ONLY_TOOL_BUDGET);
if (repairBudgetStop.isPresent()) {
    return repairBudgetStop.get();
}
```

That keeps orchestration in the stage while moving the repair/fix terminal gate
to an owner named for the behavior.

## Do Not Touch In T499

T499 must not move:

- `mutationReadOnlyBudgetExceeded(...)`;
- `ToolRepromptContextBudgetHandler.handleReadOnlyMutationEvidenceBudget(...)`;
- `CompactMutationContinuationPlanner`;
- context-budget fallback behavior;
- `ConditionalReviewFixPolicy` internals;
- `ResponseObligationVerifier` answer wording;
- `MissingMutationRetry`;
- `ExecutionOutcome`;
- approval or protected-path policy.

## T499 Test Shape

Start with RED tests for `ToolRepairInspectionBudgetGate`:

- non-repair read-only turns do not stop;
- conditional review/fix with passing current static diagnostics returns the
  existing no-change answer and clears pending obligation;
- repair/fix read-only budget exhaustion produces the existing deterministic
  repair-inspection-only answer and failure reason;
- trace records `REPAIR_INSPECTION_ONLY` with the same obligation name and
  status;
- `ToolCallRepromptStage` delegates the repair budget branch and no longer owns
  `repairReadOnlyBudgetExceeded(...)`.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepairInspectionBudgetGateTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*Repair*" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.*repair*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.*conditional*" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Later Decision

After T499, inspect mutation read-only evidence budget separately. It is
connected to compact mutation continuation and should not be moved merely
because it shares a counter with the repair/fix budget gate.
