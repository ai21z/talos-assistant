# [T590] Post-outcome local trace shape decision

## Decision

The next implementation ticket is:

`T591 Extract expectation verification trace event factory`

The implementation should extract only `EXPECTATION_VERIFIED` event construction
behind the existing `LocalTurnTraceCapture.recordExpectationVerified(...)`
facade.

Do not move expectation verification policy, expectation-kind metric selection,
static verifier behavior, action-obligation tracing, pending-obligation tracing,
generic tool-call lifecycle tracing, trace lifecycle, trace persistence,
prompt-debug lifecycle, or artifact canary scanning in T591.

## Source Evidence

Inspected from fresh `origin/v0.9.0-beta-dev` at `bff2f97f`.

| File | Lines | Why inspected |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 480 | Public trace facade and remaining inline event construction after T589. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 104 | Generic trace event helpers and payload summary behavior. |
| `src/main/java/dev/talos/runtime/trace/CommandTraceEventFactory.java` | 140 | Existing factory pattern for trace event construction. |
| `src/main/java/dev/talos/runtime/trace/TaskOutcomeTraceRecorder.java` | 46 | Outcome recorder caller that now uses the T589 outcome recorder path. |
| `src/main/java/dev/talos/runtime/verification/TaskExpectationTraceRecorder.java` | 90 | Current expectation-specific trace metric formatting owner. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 118 | Pending-obligation trace caller and state boundary. |
| `work-cycle-docs/tickets/done/[T589-done-high] extract-outcome-trace-recorder.md` | 63 | Previous ticket scope and explicit exclusions. |

## Current Shape

`LocalTurnTraceCapture` is now mostly a thread-local facade plus small lifecycle
state. The remaining non-trivial inline event construction is concentrated in
three areas:

1. `recordExpectationVerified(...)`
2. `recordActionObligation(...)`
3. `recordPendingActionObligation(...)`

`recordExpectationVerified(...)` is the cleanest next owner because it is called
only by `TaskExpectationTraceRecorder`, and that recorder already owns
expectation-kind-specific measurement selection:

- literal expectation observed hash/byte/char/line metrics
- replacement old/new presence summary
- append-line final-line metrics
- bullet-list count metrics

The trace facade still owns the generic event-shape mechanics:

- event type: `EXPECTATION_VERIFIED`
- payload keys
- null-to-empty normalization
- `pathHint` redaction
- non-negative numeric bounds

That split is now artificial. The event-shape mechanics should move into a
dedicated runtime trace factory while leaving verification behavior and
expectation metric selection untouched.

## Rejected Next Moves

### Action-obligation trace extraction

Rejected for T591.

`recordActionObligation(...)` is called across CLI retry handling, compact
continuation, `LoopState`, tool execution, review-fix policy, and inspection
budget handling. That surface is broad and policy-sensitive. It mixes action
obligation truth, terminal failure behavior, repair behavior, compact
continuation, and warning paths. It needs its own decision before movement.

### Pending-obligation trace extraction

Rejected for T591.

`PendingActionObligation` already owns raised/breached call timing and failure
wording. The remaining trace event construction is compact, but pending
obligation state is tied to `LoopState`, breach assessment, repair reprompts,
target scope, source evidence, and compact continuation paths. Do not move it
as a side quest while expectation trace event construction is cleaner.

### Generic tool-call lifecycle trace extraction

Rejected for T591.

`recordToolCallParsed(...)`, `recordToolCallBlocked(...)`,
`recordToolExecuted(...)`, and approval event facades still delegate to
`TurnTraceEvent` helpers. Moving them would be a separate lifecycle/facade
design decision, not the next narrow trace-evidence extraction.

### Trace lifecycle and persistence

Rejected for T591.

`begin(...)`, `complete(...)`, `clear()`, and `ContextLedgerCapture` integration
are lifecycle ownership, not event-shape ownership. They should not move in the
same ticket as expectation verification event construction.

## T591 Scope

T591 should:

1. Add a package-private runtime trace factory, likely
   `ExpectationVerificationTraceEventFactory`.
2. Keep `LocalTurnTraceCapture.recordExpectationVerified(...)` as the public
   facade.
3. Move only `EXPECTATION_VERIFIED` event construction, payload normalization,
   `pathHint` redaction, and non-negative metric bounding into the factory.
4. Preserve all payload keys and values exactly.
5. Preserve `TaskExpectationTraceRecorder` behavior and package ownership.
6. Add a focused ownership/regression test proving the factory owns the event
   shape and `LocalTurnTraceCapture` no longer builds the payload inline.

## Expected Verification

- RED focused ownership test before implementation.
- GREEN focused expectation trace tests after implementation.
- Existing expectation/static verifier tests unchanged.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Stop Conditions

Stop instead of broadening if source inspection during T591 shows that moving
`EXPECTATION_VERIFIED` event construction would require changing:

- expectation verification pass/fail logic;
- expectation metric selection;
- static verifier wording;
- trace event payload keys;
- path redaction behavior;
- trace lifecycle or persistence.
