# [T596] Local trace event-shape lane closeout

## Decision

Close the local trace event-shape extraction lane for now.

The next ticket should be a no-code decision ticket:

`T597 Trace Lifecycle And Persistence Ownership Decision`

Do not start another implementation extraction until that decision is recorded.

## Source Evidence

Inspected from fresh `origin/v0.9.0-beta-dev` at `9b938d5e`.

| File | Lines | Why inspected |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 466 | Public thread-local trace facade after T595. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 104 | Generic event value/helper type for tool lifecycle events. |
| `src/main/java/dev/talos/runtime/trace/ActionObligationTraceEventFactory.java` | 33 | Latest extracted event-shape owner. |
| `src/main/java/dev/talos/runtime/trace/PendingActionObligationTraceEventFactory.java` | 32 | Pending action-obligation event-shape owner. |
| `src/main/java/dev/talos/runtime/trace/TaskOutcomeTraceRecorder.java` | 46 | Outcome/verification/warning trace entrypoint. |
| `work-cycle-docs/tickets/done/[T595-done-high] extract-action-obligation-trace-event-factory.md` | 64 | Previous implementation scope and explicit exclusions. |

## Current Shape

`LocalTurnTraceCapture` is now mostly a thread-local facade and lifecycle owner.
The former event-shape responsibilities have been moved behind dedicated
runtime trace owners:

- command trace events -> `CommandTraceEventFactory`
- private-document handoff events -> `PrivateDocumentHandoffTraceEventFactory`
- permission decision events -> `PermissionTraceEventFactory`
- checkpoint recording -> `CheckpointTraceRecorder`
- protected-read postcondition events -> `ProtectedReadPostconditionTraceEventFactory`
- protocol sanitization events -> `ProtocolSanitizationTraceEventFactory`
- backend malformed-response events -> `BackendMalformedResponseTraceEventFactory`
- exact literal write correction events -> `ExactLiteralWriteCorrectionTraceEventFactory`
- path argument normalization events -> `PathArgumentNormalizationTraceEventFactory`
- tool alias decision events -> `ToolAliasDecisionTraceEventFactory`
- model response recording -> `ModelResponseTraceRecorder`
- policy trace recording -> `PolicyTraceRecorder`
- prompt audit recording -> `PromptAuditTraceRecorder`
- repair trace recording -> `RepairTraceRecorder`
- verification trace recording -> `VerificationTraceRecorder`
- outcome trace recording -> `OutcomeTraceRecorder`
- expectation verification events -> `ExpectationVerificationTraceEventFactory`
- pending action-obligation events -> `PendingActionObligationTraceEventFactory`
- action-obligation events -> `ActionObligationTraceEventFactory`

The remaining direct `LocalTurnTraceCapture` responsibilities are not the same
kind of event-shape extraction:

- trace lifecycle:
  - `begin(...)`
  - `complete()`
  - `clear()`
  - `TRACE_STARTED`
  - `TRACE_COMPLETED`
  - `ContextLedgerCapture.begin(...)`
  - `ContextLedgerCapture.complete()`
  - `ContextLedgerCapture.clear()`
- thread-local state:
  - active trace bag
  - trace id
  - turn number
  - outcome dominance guard
- warning summary facade:
  - `warning(...)`
- generic tool lifecycle facade:
  - `recordToolCallParsed(...)`
  - `recordToolCallBlocked(...)`
  - `recordToolExecuted(...)`
  - approval event facades

The generic tool lifecycle methods already delegate event construction to
`TurnTraceEvent` helpers. Moving them now would be a naming/facade reshuffle,
not a clear ownership correction.

## Rejected Next Moves

### Another event factory for generic tool lifecycle

Rejected for now.

`TurnTraceEvent` already owns the generic tool lifecycle event helpers:

- `toolCallParsed(...)`
- `toolCallBlocked(...)`
- `toolExecuted(...)`
- `approval(...)`

Adding a second factory around those helpers would add indirection without
clarifying policy or evidence ownership.

### Warning extraction

Rejected for immediate implementation.

`LocalTurnTraceCapture.warning(...)` is simple, but warning callers span task
outcome warnings, protected-read answer containment, compact continuation,
retry budget handling, and exact-write fallback. That is outcome/warning
ownership, not local trace event-shape ownership.

### Trace lifecycle extraction

Rejected as an immediate implementation.

`begin(...)`, `complete()`, `clear()`, `TRACE_STARTED`, `TRACE_COMPLETED`, and
context ledger integration are lifecycle/persistence concerns. They should be
planned as a separate ownership decision before code moves.

### Artifact canary scanning

Rejected for immediate implementation.

Runtime artifact canary scanning is adjacent to trace evidence and prompt-debug
evidence, but it is release-gate/artifact policy, not trace event-shape
construction.

## Next Lane

T597 should decide trace lifecycle and persistence ownership from source
evidence.

It should inspect:

- `LocalTurnTraceCapture`
- trace persistence/writing classes
- session log appenders
- JSON trace serialization/deserialization
- `/last trace` and explain-last-turn surfaces
- prompt-debug interactions with trace artifacts
- runtime artifact canary scanning boundaries

T597 should answer:

1. Is trace lifecycle ownership coherent where it is?
2. Should trace persistence have a clearer owner?
3. Should warning summary ownership stay generic, move to outcome ownership, or
   become its own warning recorder?
4. Is artifact canary scanning still only a release/test gate, or should it get
   a runtime-adjacent ownership decision?
5. What is the next implementation ticket, if any?

## Verification

This ticket is documentation-only. Required gates:

- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`
