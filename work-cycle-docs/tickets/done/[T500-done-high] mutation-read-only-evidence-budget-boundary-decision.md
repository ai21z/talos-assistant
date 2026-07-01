# [T500-done-high] Mutation Read-Only Evidence Budget Boundary Decision

## Status

Done.

## Scope

T500 inspects the post-T499 mutation read-only evidence budget path before any
implementation extraction.

This is a no-code decision ticket. It does not change runtime behavior,
compact mutation continuation prompts, tool narrowing, trace wording, failure
wording, approval behavior, protected-path behavior, readback containment, or
static repair behavior.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T499:

| Source | Relevant ownership |
| --- | --- |
| `ToolCallRepromptStage.reprompt(...)` | owns the orchestration order: repair inspection budget gate first, then mutation read-only evidence budget, then generic failure policy |
| `ToolCallRepromptStage.mutationReadOnlyBudgetExceeded(...)` | detects mutation turns that exhausted read-only evidence collection without mutation progress |
| `ToolCallRepromptStage.readOnlyInspectionAttemptCount(...)` | counts read-only/no-progress attempts plus suppressed redundant reads |
| `ToolCallRepromptStage.readOnlyProgressOnly(...)` | verifies all collected outcomes are successful read-only progress |
| `ToolRepromptContextBudgetHandler.handleReadOnlyMutationEvidenceBudget(...)` | owns what happens after the mutation read-only evidence budget fires |
| `ToolRepromptContextBudgetHandler.tryCompactMutationContinuation(...)` | owns compact continuation LLM call execution and no-tool stop behavior |
| `CompactMutationContinuationPlanner.planForContextBudget(...)` | owns compact continuation prompt, narrowed tools, target/readback selection, protected readback filtering, and source-evidence snippets |
| `CompactMutationContinuationPlanner.hasMutationTargets(...)` | owns whether there are concrete mutation targets for compact continuation |

Existing coverage already protects the sensitive behavior:

- `ToolCallLoopTest.singleTargetMutationReadOnlyOverInspectionUsesCompactMutationContinuation`
  verifies read-only over-inspection on a mutation request uses compact mutation
  continuation instead of the generic loop cap.
- `ToolRepromptContextBudgetHandlerTest` verifies compact continuation success,
  compact continuation no-tool stop, pending-obligation precedence, and ordinary
  context-budget fallback.
- `CompactMutationContinuationPlannerTest` verifies compact prompt construction,
  tool narrowing, similar sibling readback inclusion, source-derived evidence
  readbacks, and owner delegation.

## Decision

The next implementation ticket may extract the mutation read-only evidence
budget gate, but it must not move compact continuation planning or execution.

The coherent owner is a small gate beside the T499 repair gate:

```text
dev.talos.runtime.toolcall.ToolMutationEvidenceBudgetGate
```

The gate should own only:

- mutation read-only evidence budget applicability;
- the shared attempt-count calculation for this branch;
- the call into `ToolRepromptContextBudgetHandler.handleReadOnlyMutationEvidenceBudget(...)`.

`ToolCallRepromptStage` should continue to own ordering:

1. repair/fix inspection budget gate;
2. mutation read-only evidence budget gate;
3. generic failure policy;
4. later repair and reprompt planning.

`ToolRepromptContextBudgetHandler` should continue to own compact continuation
execution and no-tool stop behavior.

`CompactMutationContinuationPlanner` should continue to own compact prompt,
tool narrowing, readback selection, similar-target safety, protected readback
filtering, and source-evidence containment.

## Next Coherent Implementation Slice

The next implementation ticket should be:

```text
[T501] Extract mutation evidence budget gate
```

Recommended API:

```java
static Optional<Boolean> tryContinueOrStop(LoopState state, int readOnlyToolBudget)
```

Return semantics:

- `Optional.empty()` when the mutation read-only evidence budget does not apply;
- `Optional.of(true)` when compact mutation continuation produced executable
  tool calls and the loop should continue;
- `Optional.of(false)` when compact mutation continuation produced a terminal
  no-action answer.

The implementation should move these methods out of `ToolCallRepromptStage`:

- `mutationReadOnlyBudgetExceeded(...)`;
- `readOnlyInspectionAttemptCount(...)` if no longer needed by the stage;
- `readOnlyProgressOnly(...)` if no longer needed by the stage.

The implementation should not move:

- `ToolRepromptContextBudgetHandler.handleReadOnlyMutationEvidenceBudget(...)`;
- `ToolRepromptContextBudgetHandler.tryCompactMutationContinuation(...)`;
- `CompactMutationContinuationPlanner`;
- compact prompt wording;
- compact tool narrowing;
- readback truncation or protected readback filtering;
- source-derived evidence handling;
- repair/fix inspection budget handling from T499.

## T501 Test Shape

Start with RED tests for `ToolMutationEvidenceBudgetGate`:

- non-mutation read-only turns do not apply;
- mutation turns below the budget do not apply;
- mutation turns with prior mutation progress do not apply;
- mutation turns with failed calls do not apply;
- over-budget mutation read-only evidence delegates to
  `ToolRepromptContextBudgetHandler` and continues when compact continuation
  returns a write/edit tool;
- over-budget mutation read-only evidence returns a terminal no-action answer
  when compact continuation returns no executable tool call;
- `ToolCallRepromptStage` delegates the mutation budget branch and no longer
  owns `mutationReadOnlyBudgetExceeded(...)`.

Focused verification should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceBudgetGateTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.singleTargetMutationReadOnlyOverInspectionUsesCompactMutationContinuation" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Stop Condition

If T501 cannot preserve compact continuation prompt content, tool narrowing,
source-evidence readbacks, protected readback filtering, and no-tool stop
behavior exactly, it should be abandoned as too broad and replaced with a
smaller inspection ticket.

## Independent Inspection

An explorer independently inspected the same source boundary and reached the
same conclusion:

- `ToolCallRepromptStage` should keep orchestration order.
- `ToolRepromptContextBudgetHandler` should keep compact continuation
  execution.
- `CompactMutationContinuationPlanner` should keep compact prompt/tool/readback
  planning.
- The next implementation slice is a named mutation evidence budget gate, not a
  generic utility extraction.

The explorer rated the extraction as coherent provided T501 keeps the write
scope limited to the gate and its focused tests.
