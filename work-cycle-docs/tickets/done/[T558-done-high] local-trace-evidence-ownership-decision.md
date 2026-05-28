# [T558] Local trace evidence ownership decision

## Summary

T558 is a no-code inspection and decision ticket after the prompt-debug
command/artifact sublane closed in T557.

Decision: do not extract trace lifecycle, trace persistence, prompt-debug
capture, private-document handoff, trace redaction, or artifact canary scanning
yet. The next coherent implementation ticket is:

```text
[T559] Extract command trace event factory
```

The goal of T559 should be to move command event construction out of
`LocalTurnTraceCapture` while preserving the existing facade methods, event
types, event order, redaction behavior, payload fields, and command output
privacy guarantees.

## Source inspected

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 6a03baeb
talosVersion = 0.9.9
```

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 678 | Thread-local trace facade, trace lifecycle, event vocabulary bridge, context-ledger bridge, command event construction, private-document handoff event construction, prompt audit attachment, outcome/verification/warning recording. |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java` | 417 | JSON-friendly local trace value and builder. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 104 | Trace event value plus generic tool-call payload summaries. |
| `src/main/java/dev/talos/runtime/trace/TraceRedactor.java` | 241 | Trace/history redaction helpers, hashes, byte/line counts, path hints, protected/private answer redaction. |
| `src/main/java/dev/talos/runtime/trace/PromptAuditSnapshot.java` | 257 | Redacted prompt/control audit summary attached to local trace. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1305 | Runtime turn lifecycle, trace begin/complete/clear sequencing, tool execution, approval/checkpoint/command policy sequencing. |
| `src/main/java/dev/talos/runtime/TurnAuditCapture.java` | 151 | Compact turn audit collector and compatibility bridge into local trace. |
| `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java` | 158 | Post-turn persistence listener for completed local traces and turn logs. |
| `src/main/java/dev/talos/runtime/JsonSessionStore.java` | 575 | Session, turn, and trace JSON persistence and text-node sanitization. |
| `src/main/java/dev/talos/runtime/toolcall/ToolResultModelContextHandoff.java` | 259 | Protected/private tool-result model-context handoff and private-document approval trace calls. |

Focused tests inspected:

| File | Evidence |
| --- | --- |
| `src/test/java/dev/talos/runtime/trace/LocalTurnTraceCommandTest.java` | Command lifecycle trace events, command-denied trace path, raw stdout/stderr privacy. |
| `src/test/java/dev/talos/runtime/trace/LocalTurnTraceContextLedgerTest.java` | Trace completion includes context-ledger summaries without raw private/command text. |
| `src/test/java/dev/talos/runtime/trace/TaskOutcomeTraceRecorderTest.java` | Outcome, verification, and warnings already have a separate recorder. |

## Current measurements

Broad search over `src/main/java`, `src/test/java`, and `src/e2eTest/java`:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 388 |
| Files containing `LocalTurnTraceCapture.` | 42 |
| `recordCommand` | 30 |
| `recordPrivateDocumentModelHandoff` | 10 |
| `PromptAuditSnapshot` | 39 |
| `JsonTurnLogAppender` | 26 |
| `saveTrace(` | 9 |
| `TraceRedactor` | 54 |
| `ContextLedgerCapture` | 30 |

This confirms the trace surface is still broad. The right next move is not a
wholesale `LocalTurnTraceCapture` move.

## Ownership decisions

### Trace lifecycle

Owner: runtime turn orchestration plus `LocalTurnTraceCapture` facade.

`TurnProcessor` starts the turn-local evidence chain with
`TurnUserRequestCapture`, `TurnAuditCapture`, and `LocalTurnTraceCapture`. It
also completes the trace, embeds it in `TurnAudit`, and clears thread-local
state in `finally`.

`LocalTurnTraceCapture.begin(...)` starts `ContextLedgerCapture`; `complete()`
completes it and attaches the context-ledger summary to the trace. `TurnProcessor`
also uses `LocalTurnTraceCapture.currentTraceId()` and `currentTurnNumber()` for
checkpoint metadata.

Decision: do not extract trace lifecycle in the next ticket. It crosses turn
ordering, context-ledger cleanup, checkpoint metadata, audit capture, and trace
persistence timing.

### Trace persistence

Owner: `JsonTurnLogAppender`, `SessionStore`, and `JsonSessionStore`.

`JsonTurnLogAppender` persists completed local traces from `TurnAudit`.
`SessionStore` defines the trace persistence API. `JsonSessionStore` owns trace
directory naming, file naming, latest-trace lookup, trace loading, and final
JSON text-node sanitization before writes.

Decision: leave trace persistence alone. It is already a coherent boundary and
is not the source of the current mixed responsibility.

### Trace value and generic event value

Owner: `LocalTurnTrace` and `TurnTraceEvent`.

`LocalTurnTrace` is a JSON-friendly artifact value. `TurnTraceEvent` is the
generic event value and generic tool-call payload summary helper.

Decision: do not move event-family-specific command behavior into
`TurnTraceEvent`. That would turn a value type into another behavior warehouse.
Event-family construction should live in dedicated helpers behind the current
facade.

### Trace redaction

Owner: `TraceRedactor` for trace/history redaction primitives.

`TraceRedactor` already owns trace-level hashes, byte counts, line counts, path
hints, secret-like assignment redaction, protected-read answer redaction, and
private-document answer redaction.

Decision: do not split trace redaction next. Redaction touches prompt-debug,
session persistence, local trace, protected/private document policy, and artifact
canary gates. A premature split would blur the release safety boundary.

### Prompt audit attachment

Owner: `PromptAuditSnapshot` plus `LocalTurnTraceCapture.recordPromptAudit(...)`.

`PromptAuditSnapshot` owns compact prompt/control audit content. The trace
facade attaches it to the current trace and emits the `PROMPT_AUDIT_RECORDED`
event.

Decision: do not move prompt audit next. It is already a data-owner plus facade
call pattern and is not the most confused event family.

### Outcome and verification evidence

Owner: `TaskOutcomeTraceRecorder` plus `LocalTurnTraceCapture` facade.

T402 through T406 already extracted runtime outcome warning, annotation,
rendering, and trace recording responsibilities. `TaskOutcomeTraceRecorder`
records verification, warnings, and final outcome through the trace facade.

Decision: do not rework outcome/verification trace in this lane.

### Private-document handoff events

Owner for handoff decision: `ToolResultModelContextHandoff`.

`ToolResultModelContextHandoff` owns the decision to request per-turn approval
for private document model handoff and records required/granted/denied trace
events through `LocalTurnTraceCapture`.

Decision: do not extract private-document handoff trace events first. The event
payload is coherent, but the surrounding behavior is privacy-sensitive and tied
to approval semantics, content metadata, private mode, and model-context
handoff. It should be handled only after the simpler command event-family
extraction proves the pattern.

### Command trace event construction

Current owner: `LocalTurnTraceCapture`.

Target owner: a dedicated trace helper behind the current facade, such as
`dev.talos.runtime.trace.CommandTraceEventFactory`.

`LocalTurnTraceCapture` currently owns these command-specific concerns:

- `COMMAND_PLAN_CREATED`;
- `COMMAND_POLICY_DECISION`;
- `COMMAND_APPROVAL_REQUIRED`;
- `COMMAND_APPROVAL_GRANTED`;
- `COMMAND_APPROVAL_DENIED`;
- `COMMAND_DENIED`;
- `COMMAND_STARTED`;
- `COMMAND_OUTPUT_TRUNCATED`;
- `COMMAND_KILLED`;
- `COMMAND_TIMED_OUT`;
- `COMMAND_COMPLETED`;
- `COMMAND_FAILED`;
- command plan payload fields;
- command result payload fields;
- command display string capping;
- command argv hash;
- stdout/stderr byte and hash fields;
- stdout/stderr truncation flags;
- redaction-applied flag;
- error hash.

This is one coherent event family. It is currently embedded in the large
thread-local trace facade, but it does not need to be. Extracting only command
event construction keeps call sites stable and does not alter runtime command
policy, approval, checkpointing, command execution, output rendering, or trace
persistence.

Decision: T559 should extract command event construction behind
`LocalTurnTraceCapture`.

## Rejected immediate tickets

### Extract trace lifecycle coordinator

Rejected. Too broad and too risky for this lane. It would cross
`TurnProcessor`, `TurnAuditCapture`, `LocalTurnTraceCapture`,
`ContextLedgerCapture`, checkpoint metadata, `TurnAudit`, and persistence
listeners.

### Move trace persistence

Rejected. `JsonTurnLogAppender`, `SessionStore`, and `JsonSessionStore` are
already coherent enough. Persistence work would be a separate design lane.

### Move prompt-debug capture lifecycle

Rejected. T557 already closed the prompt-debug command/artifact sublane and
rejected capture lifecycle movement for now.

### Move private-document handoff events

Rejected for the next ticket. The event family is real, but the surrounding
privacy and approval semantics are more sensitive than command event payload
construction.

### Move artifact canary scanning

Rejected. The canary scanner is a broad deterministic release/audit backstop,
not a local trace event-family owner.

### Extract all trace event vocabulary at once

Rejected. `LocalTurnTraceCapture` has 388 matching call lines across 42 files.
A broad event-sink migration would be churn and could weaken trace coverage.

## Selected next ticket

```text
[T559] Extract command trace event factory
```

Implementation shape:

- Create a package-local command trace event owner in
  `dev.talos.runtime.trace`.
- Move only command event construction and command payload construction out of
  `LocalTurnTraceCapture`.
- Keep all public `LocalTurnTraceCapture.recordCommand...` methods in place.
- Preserve event type strings exactly.
- Preserve event order exactly, including separate output-truncated and killed
  events before the final completed/failed/timed-out event.
- Preserve payload keys and values exactly.
- Preserve raw stdout/stderr exclusion from trace artifacts.
- Do not change command policy, approval flow, checkpoint behavior,
  `RunCommandTool`, command rendering, trace persistence, or private-document
  handoff behavior.

Focused tests for T559:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceContextLedgerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.command.*" --no-daemon
```

T559 should also include an ownership regression proving
`LocalTurnTraceCapture` no longer owns `commandPlanData`,
`commandResultData`, or direct command display payload construction.

Standard gate for T559:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance criteria

- T558 makes no runtime code changes.
- Local trace lifecycle, persistence, value types, redaction, prompt audit,
  outcome/verification trace, private-document handoff, and command events are
  documented from source evidence.
- Immediate risky moves are explicitly rejected.
- The next implementation ticket is selected as `[T559] Extract command trace
  event factory`.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
