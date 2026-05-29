# [T594] Post-pending-obligation local trace shape decision

## Decision

The next implementation ticket is:

`T595 Extract action obligation trace event factory`

The implementation should extract only `ACTION_OBLIGATION_EVALUATED` event
construction behind the existing
`LocalTurnTraceCapture.recordActionObligation(...)` facades.

Do not move action-obligation policy, caller timing, failure decisions, repair
policy, retry behavior, terminal failure behavior, warning ownership, generic
tool-call lifecycle tracing, trace lifecycle, trace persistence, prompt-debug
lifecycle, or artifact canary scanning in T595.

## Source Evidence

Inspected from fresh `origin/v0.9.0-beta-dev` at `c8099344`.

| File | Lines | Why inspected |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 473 | Public trace facade and remaining inline action-obligation event construction after T593. |
| `src/main/java/dev/talos/runtime/trace/PendingActionObligationTraceEventFactory.java` | 32 | Latest extracted event-shape owner. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 104 | Generic event helper and payload summary behavior. |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java` | 493 | Tool execution action-obligation trace caller. |
| `src/main/java/dev/talos/runtime/toolcall/LoopState.java` | 181 | Static repair action-obligation failure callers and terminal failure state. |
| `src/main/java/dev/talos/cli/modes/MissingMutationRetry.java` | 847 | Largest action-obligation caller surface and retry/failure wording owner. |
| `work-cycle-docs/tickets/done/[T593-done-high] extract-pending-action-obligation-trace-event-factory.md` | 66 | Previous ticket scope and explicit exclusions. |

## Current Shape

After T593, pending action-obligation event construction is no longer owned by
`LocalTurnTraceCapture`. The remaining inline action-obligation trace event
construction is:

- `recordActionObligation(String obligation, String status, String reason)`
- `recordActionObligation(String obligation, String status, String reason,
  String failureKind)`

Both facades emit the same event type:

`ACTION_OBLIGATION_EVALUATED`

Both share the same mandatory payload keys:

- `obligation`
- `status`
- `reason`

The second overload conditionally adds:

- `failureKind`

That event-shape logic is small, stable, and trace-specific. It can move into a
dedicated runtime trace factory without touching any caller behavior.

## Caller Surface

The caller surface is intentionally broad:

- `AssistantTurnExecutor` records selected action obligations.
- `MissingMutationRetry` records retry outcomes, blocked retry outcomes,
  wrong-tool static repair failures, context-budget skips, and final retry
  failures.
- `ExactWriteContextFallback` records compact-context retry behavior.
- `CompactMutationContinuationExecutor` records compact continuation no-tool
  failures.
- `LoopState` records static repair invalid-write and selector-repair failures.
- `ToolCallExecutionStage` records source-evidence and append-line obligation
  failures or repairs.
- `ToolRepairInspectionBudgetGate` records repair-inspection-only failures.
- `ConditionalReviewFixPolicy` records inspection-satisfied review-fix
  obligations.

That breadth means action-obligation policy must not move in T595. It does not
mean the event payload factory must stay inline in the thread-local facade.

## Rejected Next Moves

### Moving action-obligation policy

Rejected for T595.

The statuses and failure kinds are authored by separate policy owners. T595
must not centralize, rename, validate, reinterpret, or reorder them.

### Moving caller timing

Rejected for T595.

Each caller records a different lifecycle moment: selected, unsatisfied,
retried, repaired, blocked, failed, or inspection-satisfied. Those timings stay
with their current owners.

### Generic tool-call lifecycle trace extraction

Rejected for T595.

`recordToolCallParsed(...)`, `recordToolCallBlocked(...)`,
`recordToolExecuted(...)`, and approval event facades still delegate to
`TurnTraceEvent`. That is a separate lifecycle/facade decision.

### Warning ownership

Rejected for T595.

Warning call sites span task outcome warnings, protected-read answer
containment, compact continuation, retry budget handling, and exact-write
fallback. Warning ownership is not part of action-obligation event-shape
construction.

### Trace lifecycle and persistence

Rejected for T595.

`begin(...)`, `complete(...)`, `clear()`, `TRACE_STARTED`,
`TRACE_COMPLETED`, and `ContextLedgerCapture` integration are trace lifecycle,
not action-obligation event-shape ownership.

## T595 Scope

T595 should:

1. Add a package-private runtime trace factory, likely
   `ActionObligationTraceEventFactory`.
2. Keep both `LocalTurnTraceCapture.recordActionObligation(...)` overloads as
   public facades.
3. Move only event payload construction, string normalization, optional
   `failureKind` handling, and `ACTION_OBLIGATION_EVALUATED` event emission
   into the factory.
4. Preserve event type, payload keys, and values exactly.
5. Preserve all caller behavior, status strings, failure kinds, final answers,
   failure decisions, warnings, and retry behavior.
6. Add focused tests for the no-failure-kind and failure-kind event shapes.
7. Add an ownership regression proving `LocalTurnTraceCapture` no longer builds
   the action-obligation payload inline.

## Expected Verification

- RED focused ownership test before implementation.
- GREEN focused action-obligation trace tests after implementation.
- Focused existing tests around static repair failure, repair-inspection-only,
  source-evidence failures, and exact-write compact fallback.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Stop Conditions

Stop instead of broadening if T595 source inspection shows the extraction would
require changing:

- status strings;
- failure-kind strings;
- failure decision behavior;
- retry behavior;
- final answer wording;
- warning behavior;
- event type names;
- payload keys;
- trace lifecycle or persistence.
