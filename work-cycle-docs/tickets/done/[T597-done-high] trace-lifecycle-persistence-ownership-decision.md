# [T597] Trace lifecycle and persistence ownership decision

## Decision

Do not extract trace lifecycle or trace persistence yet.

The post-T596 local trace shape is coherent enough to stop the trace-event
lane without another implementation ticket. The remaining responsibilities are
not event-shape construction. They are turn lifecycle, completed-audit handoff,
session persistence, debug rendering, and release-gate artifact scanning.

The next ticket should be a no-code decision ticket:

`T598 Runtime Artifact Canary Ownership Decision`

Do not start an implementation ticket until that decision inspects the current
canary scanner, Gradle gates, manual-audit roots, prompt-debug artifacts, trace
artifacts, session artifacts, and allowlist behavior.

## Source Evidence

Inspected from fresh `origin/v0.9.0-beta-dev` at `16166a5d`.

| File | Lines | Why inspected |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 466 | Thread-local local-trace facade, trace start/complete/clear lifecycle, context-ledger coupling, and warning facade. |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java` | 417 | Local trace artifact schema, builder summaries, warnings, redaction summary, and event collection model. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1305 | Runtime turn lifecycle owner that begins, completes, and clears local trace capture. |
| `src/main/java/dev/talos/runtime/TurnAudit.java` | 63 | Completed-turn audit object carrying the completed local trace out of thread-local state. |
| `src/main/java/dev/talos/runtime/TurnResult.java` | 39 | Runtime result boundary that carries `TurnAudit` to post-turn listeners. |
| `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java` | 158 | Post-turn persistence listener that saves the completed local trace and appends the structured turn record. |
| `src/main/java/dev/talos/runtime/SessionStore.java` | 69 | Persistence seam for sessions, turn logs, and local trace artifacts. |
| `src/main/java/dev/talos/runtime/JsonSessionStore.java` | 575 | File-backed session store, turn JSONL persistence, trace save/load/delete, and persisted JSON sanitization. |
| `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java` | 475 | `/last trace` rendering surface that joins the latest turn record with its local trace artifact. |
| `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java` | 128 | Prompt-debug command surface, distinct from local trace lifecycle and persistence. |
| `src/main/java/dev/talos/spi/types/PromptDebugCapture.java` | 78 | Process-local prompt-debug lifecycle and user-facing/background capture filtering. |
| `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java` | 148 | Deterministic runtime/generated artifact canary scanner. |
| `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanCli.java` | 100 | CLI wrapper used by Gradle/runtime artifact scan gates. |
| `build.gradle.kts` | 2278 | Generated-artifact and targeted runtime-artifact canary scan tasks. |
| `work-cycle-docs/tickets/done/[T596-done-high] local-trace-event-shape-lane-closeout.md` | 153 | Prior lane closeout and questions for this decision. |

## Current Ownership Model

### Turn lifecycle

`TurnProcessor` owns runtime turn boundaries:

- create a trace id with `LocalTurnTraceCapture.newTraceId()`;
- call `LocalTurnTraceCapture.begin(...)` before executing the turn;
- call `LocalTurnTraceCapture.complete()` after outcome recording;
- attach the resulting `LocalTurnTrace` to `TurnAudit`;
- clear local trace capture in `finally`.

That is the right owner. The runtime processor already owns the real turn
boundary, and moving begin/complete into a separate lifecycle object would
mostly hide the actual critical section.

### Thread-local trace assembly

`LocalTurnTraceCapture` owns process-local trace assembly:

- the active builder bag;
- current trace id and turn number;
- outcome dominance guard;
- context-ledger begin/complete/clear coupling;
- public recording facade methods used across runtime code.

This is still a large facade, but it is now large for a legitimate reason:
it is the stable runtime entrypoint for trace recording. The event-shape
responsibilities have already moved to dedicated recorders/factories.

### Completed-audit handoff

`TurnAudit` is the correct handoff object. It carries the completed local trace
out of thread-local state and into `TurnResult` without forcing post-turn
listeners to know about `LocalTurnTraceCapture`.

That means post-turn persistence is already decoupled from the active trace
thread-local.

### Trace persistence

`JsonTurnLogAppender` is the post-turn bridge:

- if a `TurnAudit.localTrace()` exists, save it through `SessionStore`;
- append the structured turn JSONL record with the `traceId`;
- swallow/log persistence failures so disk problems do not abort a live turn.

`SessionStore` is the persistence seam. `JsonSessionStore` is the concrete
file-backed implementation:

- saves trace artifacts under `sessions/traces/<sessionId>/`;
- names files with turn number plus sanitized trace id;
- loads by trace id;
- loads latest trace by filename order;
- deletes trace artifacts when a session is deleted;
- sanitizes persisted JSON text nodes before writing.

This is not currently crying out for extraction. A `LocalTracePersistence`
wrapper around one `store.saveTrace(...)` call would be a pass-through and
would weaken locality without adding policy.

### `/last trace`

`ExplainLastTurnCommand` is a CLI rendering surface, not trace persistence.
It loads the latest active-session turn record, then loads the trace by the
turn record's `traceId` for the `trace` view.

That is the right direction: the command renders persisted evidence; it does
not own capture or persistence.

### Prompt-debug lifecycle

`PromptDebugCapture` is process-local prompt/provider request capture. Its
lifecycle is separate from local turn trace:

- `PromptDebugCapture.beginTurn()` resets the latest user-facing prompt capture
  at assistant-turn execution start;
- provider clients record prompt/provider snapshots;
- `PromptDebugCommand` renders or saves prompt-debug artifacts.

Do not merge prompt-debug lifecycle with local trace lifecycle. Prompt-debug is
provider-request evidence. Local trace is runtime turn evidence. They should
remain correlated by audit procedure, not collapsed into one runtime object.

### Warnings

`LocalTurnTraceCapture.warning(...)` should stay as a generic trace warning
facade for now.

Warnings are produced by multiple owners:

- task outcome warnings;
- protected-read answer containment;
- context-budget retry handling;
- exact-write fallback;
- compact continuation and retry paths.

That is not one clean trace-lifecycle responsibility. Moving warnings now would
either create a generic pass-through recorder or force unrelated outcome and
repair policy into one warning owner.

### Artifact canary scanning

Artifact canary scanning is adjacent to trace evidence, but it is not trace
lifecycle.

The current scanner and Gradle tasks behave like release/test gates:

- `ArtifactCanaryScanner` scans targeted text-like artifact roots;
- `ArtifactCanaryScanCli` wraps the scanner for task execution;
- `checkGeneratedArtifactCanaries` scans generated verification reports during
  normal `check`;
- `checkRuntimeArtifactCanaries` requires explicit `artifactScanRoots` so old
  ignored manual-audit artifacts are not scanned accidentally.

That ownership deserves its own decision before any implementation. The risk is
not event-shape coupling; the risk is release-gate semantics, scan-root
selection, allowlist provenance, and which artifact classes count as runtime
evidence.

## Rejected Moves

### Extract trace lifecycle from `LocalTurnTraceCapture`

Rejected.

`begin(...)`, `complete()`, and `clear()` are short but safety-critical because
they pair active trace state with context-ledger state. Moving them without a
new lifecycle requirement would add indirection to the exact code that must
remain easy to audit.

### Extract trace persistence from `JsonTurnLogAppender`

Rejected.

`JsonTurnLogAppender` currently has the right role: post-turn persistence
listener. `SessionStore` already abstracts trace persistence. A new class would
mostly wrap:

```text
if (audit.localTrace() != null) store.saveTrace(sessionId, audit.localTrace())
```

That is not a real ownership improvement.

### Move `/last trace` into runtime

Rejected.

`ExplainLastTurnCommand` is CLI rendering. It can load persisted runtime
evidence through `SessionStore`, but formatting user-visible debug output is
not a runtime responsibility.

### Merge prompt-debug lifecycle and local trace lifecycle

Rejected.

Prompt-debug captures provider request evidence. Local trace captures runtime
turn evidence. They are related audit artifacts, but their lifecycles and
privacy surfaces are different.

### Extract generic warning recording now

Rejected.

Warning ownership cuts across outcome, verification, protected-read, fallback,
and continuation policy. It should not be moved under the trace lifecycle lane.

### Wire artifact canary scanning into live runtime turns now

Rejected.

Runtime artifact canary scanning is a gate over artifact roots, not a per-turn
capture concern. Moving it into live turns without deciding release/test
semantics would blur audit policy and runtime behavior.

## Answers To T596 Questions

1. Trace lifecycle ownership is coherent enough where it is. `TurnProcessor`
   owns the runtime boundary; `LocalTurnTraceCapture` owns thread-local capture
   and context-ledger pairing; `TurnAudit` carries completed evidence forward.
2. Trace persistence already has a clear enough seam: `SessionStore` is the
   abstraction, `JsonSessionStore` is the file-backed implementation, and
   `JsonTurnLogAppender` is the post-turn bridge.
3. Warning summaries should stay behind the generic `LocalTurnTraceCapture`
   facade for now. Their true ownership is outcome/fallback-policy dependent,
   not trace-lifecycle dependent.
4. Artifact canary scanning is still a release/test gate. It should get its
   own ownership decision before any runtime-adjacent implementation.
5. The next ticket is `T598 Runtime Artifact Canary Ownership Decision`.

## T598 Scope

T598 should be no-code.

It should inspect:

- `ArtifactCanaryScanner`;
- `ArtifactCanaryScanCli`;
- `checkGeneratedArtifactCanaries`;
- `checkRuntimeArtifactCanaries`;
- prompt-debug artifact writing;
- local trace persistence;
- session and turn JSONL persistence;
- manual-audit scripts and runbooks that call artifact scans;
- existing artifact canary tests.

It should decide:

- whether artifact canary scanning remains purely a release/test gate;
- whether scan-root selection needs a dedicated manifest/resolver owner;
- whether allowlist provenance needs stronger structure;
- whether runtime/session/prompt-debug artifact classes should share a typed
  evidence-root model;
- what the next implementation ticket is, if any.

## Verification

This ticket is documentation-only. Required gates:

- `git diff --check`
- `validateArchitectureBoundaries`
- full `check`
