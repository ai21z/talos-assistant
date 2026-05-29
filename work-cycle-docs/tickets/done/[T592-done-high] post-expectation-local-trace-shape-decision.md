# [T592] Post-expectation local trace shape decision

## Decision

The next implementation ticket is:

`T593 Extract pending action obligation trace event factory`

The implementation should extract only pending action-obligation event
construction behind the existing
`LocalTurnTraceCapture.recordPendingActionObligation(...)` facade.

Do not move pending-obligation state, breach assessment, failure wording,
reprompt policy, action-obligation tracing, generic tool-call lifecycle tracing,
warning ownership, trace lifecycle, trace persistence, prompt-debug lifecycle,
or artifact canary scanning in T593.

## Source Evidence

Inspected from fresh `origin/v0.9.0-beta-dev` at `c79a303e`.

| File | Lines | Why inspected |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 479 | Public trace facade and remaining inline event construction after T591. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 104 | Generic event helper and payload summary behavior. |
| `src/main/java/dev/talos/runtime/trace/ExpectationVerificationTraceEventFactory.java` | 43 | Latest extracted event-shape owner. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 121 | Single semantic caller of pending action-obligation trace events. |
| `src/main/java/dev/talos/runtime/toolcall/LoopState.java` | 181 | Pending obligation state, raised/breached timing, and terminal failure behavior. |
| `work-cycle-docs/tickets/done/[T591-done-high] extract-expectation-verification-trace-event-factory.md` | 69 | Previous ticket scope and explicit exclusions. |

## Current Shape

After T591, `LocalTurnTraceCapture` has no remaining expectation event-shape
ownership. The remaining inline trace event construction worth considering is:

1. `recordActionObligation(...)`
2. `recordPendingActionObligation(...)`
3. `TRACE_STARTED` / `TRACE_COMPLETED` lifecycle events
4. warning summary recording
5. generic tool-call lifecycle facades backed by `TurnTraceEvent`

`recordPendingActionObligation(...)` is now the cleanest next implementation
slice. It is called only by `PendingActionObligation.recordRaised(...)` and
`PendingActionObligation.recordBreached(...)`.

The stateful, safety-sensitive parts already belong elsewhere:

- `LoopState` owns pending-obligation lifetime and terminal failure behavior.
- `PendingActionObligationBreachGuard` owns invalid-tool-call breach
  assessment.
- `PendingActionObligation` owns raised/breached caller timing and failure
  wording.

The trace facade still owns only the event-shape mechanics:

- mapping status to event type:
  - `RAISED` -> `PENDING_ACTION_OBLIGATION_RAISED`
  - `BREACHED` -> `PENDING_ACTION_OBLIGATION_BREACHED`
  - fallback -> `PENDING_ACTION_OBLIGATION_EVALUATED`
- payload keys:
  - `status`
  - `kind`
  - `targets`
  - `reason`
- null-to-empty string normalization
- null-safe target list copying

That event-shape ownership can move without touching policy.

## Rejected Next Moves

### Action-obligation trace extraction

Rejected for T593.

`recordActionObligation(...)` remains broad. Current callers span:

- CLI retry handling in `MissingMutationRetry`
- exact-write fallback handling in `ExactWriteContextFallback`
- compact mutation continuation
- `LoopState` static repair failure paths
- `ToolCallExecutionStage`
- conditional review-fix policy
- repair inspection budget handling
- `AssistantTurnExecutor`

That surface mixes repair truth, compact continuation, terminal failure, static
repair invalid-write handling, review-fix policy, and command/tool execution
truth. It should get a separate decision before movement.

### Generic tool-call lifecycle trace extraction

Rejected for T593.

`recordToolCallParsed(...)`, `recordToolCallBlocked(...)`,
`recordToolExecuted(...)`, and approval event facades still delegate to
`TurnTraceEvent`. Moving them is a lifecycle/facade design decision, not the
same owner as pending obligation events.

### Warning ownership

Rejected for T593.

`LocalTurnTraceCapture.warning(...)` is intentionally generic right now. Warning
call sites span task outcome warnings, protected-read answer containment,
compact continuations, retry budget handling, and exact-write fallback. That is
not the same ownership unit as pending obligation event construction.

### Trace lifecycle and persistence

Rejected for T593.

`begin(...)`, `complete(...)`, `clear()`, `TRACE_STARTED`,
`TRACE_COMPLETED`, and `ContextLedgerCapture` integration are trace lifecycle,
not pending obligation event-shape ownership.

## T593 Scope

T593 should:

1. Add a package-private runtime trace factory, likely
   `PendingActionObligationTraceEventFactory`.
2. Keep `LocalTurnTraceCapture.recordPendingActionObligation(...)` as the
   public facade.
3. Move only event type selection, payload construction, target list copying,
   and string normalization into the factory.
4. Preserve event types, payload keys, and values exactly.
5. Preserve `PendingActionObligation`, `LoopState`, and
   `PendingActionObligationBreachGuard` behavior.
6. Add focused tests proving raised, breached, and fallback statuses keep the
   current event shape.
7. Add ownership regression proving `LocalTurnTraceCapture` no longer builds the
   pending-obligation payload inline.

## Expected Verification

- RED focused ownership test before implementation.
- GREEN focused pending-obligation trace tests after implementation.
- Existing tool-loop pending-obligation tests unchanged.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Stop Conditions

Stop instead of broadening if T593 source inspection shows the extraction would
require changing:

- pending obligation state lifetime;
- raised/breached timing;
- breach assessment;
- terminal failure behavior;
- failure answer or failure reason wording;
- event type names;
- payload keys;
- warning behavior;
- trace lifecycle or persistence.
