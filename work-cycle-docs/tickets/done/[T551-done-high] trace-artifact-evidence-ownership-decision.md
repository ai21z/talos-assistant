# [T551-done-high] Trace And Artifact Evidence Ownership Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T551`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `e24a69ca`
Predecessor: `T550`

## Scope

T551 is a no-code decision and inventory ticket for the trace/artifact evidence
lane selected by T550.

It intentionally does not extract code. The goal is to decide ownership before
touching safety-sensitive trace, prompt-debug, provider-body, and artifact
persistence behavior.

## Source Inspection

Commands used:

```powershell
git status --short --branch
git rev-parse --short HEAD
git rev-parse --short origin/v0.9.0-beta-dev

rg -n "LocalTurnTraceCapture\\.|PromptDebugCapture|PromptDebugInspector|redactedProviderBodyJson|ArtifactCanaryScanner|saveTrace\\(|loadTrace\\(|loadLatestTrace\\(|ToolContentMetadata|rawArtifactPersistenceAllowed|ContextLedgerCapture" `
  src/main/java src/test/java src/e2eTest/java

rg -n "^\\s*public static|^\\s*private static|^\\s*public record|^\\s*private record|^\\s*static final class|^\\s*private static final" `
  src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java `
  src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java `
  src/main/java/dev/talos/runtime/trace/TraceRedactor.java `
  src/main/java/dev/talos/runtime/trace/PromptAuditSnapshot.java
```

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `e24a69ca`:

| Source | Current role |
| --- | --- |
| `TurnProcessor` | Starts turn-local runtime evidence capture: `TurnUserRequestCapture`, `TurnAuditCapture`, `LocalTurnTraceCapture`; completes the local trace and embeds it in `TurnAudit`. |
| `LocalTurnTraceCapture` | Static thread-local trace facade, event vocabulary bridge, context-ledger lifecycle bridge, outcome/repair/verification/warning recorder. |
| `LocalTurnTrace` | JSON-friendly local trace value and builder. |
| `TurnTraceEvent` | Basic redacted event value and generic tool-call payload summaries. |
| `TraceRedactor` | Trace/history redaction helpers for secret-like assignments, protected reads, document extraction answers, path hints, hashes, byte counts, and line counts. |
| `PromptAuditSnapshot` | Redacted prompt/control audit summary attached to local trace and `/last trace` style reporting. |
| `PromptDebugCapture` | SPI-level process-local holder for latest user-facing and latest recorded prompt-debug snapshots. |
| `PromptDebugInspector` | CLI maintainer display formatter and provider-body/message redactor for prompt-debug output. |
| `PromptDebugCommand` | `/prompt-debug` CLI command, file save location, and redacted artifact emission. |
| `SessionStore` / `JsonSessionStore` | Trace persistence API and JSON file implementation with text-node sanitization. |
| `JsonTurnLogAppender` | Post-turn listener that persists completed local traces and turn logs. |
| `ToolContentMetadata` | Provenance and handoff metadata for tool output, including raw artifact persistence and RAG/index flags. |
| `PrivateDocumentContentPolicy` | Core private document content policy for model handoff, raw artifact persistence, and RAG indexing. |
| `ArtifactCanaryScanner` / `ArtifactCanaryScanCli` | Deterministic generated-artifact canary scanner and release-task CLI. |

Broad source/test/e2e search across trace, prompt-debug, provider-body,
artifact, and metadata terms found 679 matching lines. The largest clusters
are tests and orchestration surfaces:

| Cluster | Matching lines |
| --- | ---: |
| `AssistantTurnExecutorTest` | 133 |
| `ToolCallLoopTest` | 63 |
| `TurnProcessor` | 40 |
| `PromptDebugCommandTest` | 23 |
| `LocalTurnTraceContextLedgerTest` | 16 |
| `ExecutionOutcomeTest` | 15 |
| `ToolContentMetadata` | 15 |
| `ArtifactCanaryScanTest` | 14 |
| `ToolResultModelContextHandoff` | 13 |
| `PromptDebugCommand` | 11 |
| `LocalTurnTraceCapture` | 10 |
| `AssistantTurnExecutor` | 9 |

This is not one class waiting to be moved. It is an evidence system made of
several ownership seams.

## Ownership Decisions

### Turn Trace Lifecycle

Owner: runtime turn orchestration.

`TurnProcessor` owns the live turn lifecycle. It starts trace capture, completes
the trace after mode dispatch, embeds the trace in `TurnAudit`, and clears
thread-local state in `finally`.

`LocalTurnTraceCapture` should remain the thread-local trace facade for now.
It currently starts and completes `ContextLedgerCapture` as part of the same
trace lifecycle. Moving that lifecycle casually would touch runtime turn
ordering, audit capture, tool execution, context ledger cleanup, and trace
persistence timing.

Decision: do not extract a broad trace lifecycle coordinator yet.

### Local Trace Event Vocabulary

Owner: `LocalTurnTraceCapture` facade plus event-family helpers over time.

`LocalTurnTraceCapture` should remain the public compatibility facade for
recording events. It has too many call sites to move as one unit. The right
future pattern is to extract event-family builders behind the facade only when
the event family is coherent and covered by focused tests.

Command event payloads and private-document handoff events are possible later
candidates. They are not the first ticket because prompt-debug artifact safety
has a cleaner UI/redaction split and stronger release-trust payoff.

Decision: no broad typed-event-sink migration in T552.

### Prompt-Debug Lifecycle

Owner: SPI capture holder plus LLM/engine recorders.

`PromptDebugCapture` stays in `dev.talos.spi.types` for beta compatibility
because both the core LLM client and engine adapters record snapshots there.
`AssistantTurnExecutor` currently calls `PromptDebugCapture.beginTurn()` at
the start of a user-visible assistant turn. That is awkward but acceptable
until the prompt-debug lifecycle is redesigned as part of a larger runtime
turn evidence service.

Decision: do not move `PromptDebugCapture` or the begin/record lifecycle in
the next implementation ticket.

### Prompt-Debug Rendering And Redaction

Current owner: `PromptDebugInspector`.

Target owner:

- `PromptDebugInspector` should own maintainer display composition.
- A new CLI prompt-debug redaction owner should own protected/private message
  redaction and provider-body JSON redaction behind the existing inspector
  facade.

Reason: `PromptDebugInspector` currently mixes two different responsibilities:

1. rendering useful maintainer diagnostics such as task contract, expected
   target coverage, exact-literal coverage, message sections, and context
   ledger display;
2. enforcing safety for prompt-debug artifacts, including protected tool result
   redaction, protected assistant answer redaction, private document canary
   redaction, provider-body JSON traversal, and protected path detection.

Those are not the same owner. The redaction behavior is artifact-safety policy.
The formatting behavior is CLI maintainer UI.

Decision: T552 should extract prompt-debug redaction behind the current
`PromptDebugInspector` facade.

### Trace Persistence

Owner: `SessionStore` API and `JsonSessionStore` implementation.

`JsonTurnLogAppender` is correctly responsible for persisting the completed
local trace after a turn. `JsonSessionStore` correctly owns trace file naming,
trace loading, latest trace lookup, and final text-node sanitization before
writing JSON. This should not be moved in T552.

Decision: leave trace persistence alone.

### Raw Artifact Persistence Policy

Owner: content provenance policy.

`ToolContentMetadata` carries `modelHandoffAllowed`,
`rawArtifactPersistenceAllowed`, and `ragIndexAllowed`. For extracted documents,
`PrivateDocumentContentPolicy` owns the policy facts that determine those
flags. The runtime handoff layer consumes the metadata; artifact persistence
must not infer privacy only from output text.

Decision: leave raw artifact persistence policy alone until a later ticket
specifically targets private document artifact persistence.

### Artifact Canary Gates

Owner: `ArtifactCanaryScanner` plus release/runtime audit callers.

`ArtifactCanaryScanner` is already a coherent deterministic scanner. Tests
cover prompt-debug, provider-body, session, trace, turn JSONL, command-output,
report, private-document fact, and CLI task failure cases.

Decision: do not refactor the scanner now. It remains the release/audit
backstop, not the primary owner of redaction.

## Next Implementation Ticket

The next implementation ticket should be:

```text
[T552] Extract prompt-debug redaction owner
```

Proposed implementation shape:

- Create a package-local `dev.talos.cli.prompt.PromptDebugRedactor`.
- Move protected/private message redaction and provider-body JSON redaction
  mechanics out of `PromptDebugInspector`.
- Keep the current public `PromptDebugInspector.format(...)` and
  `PromptDebugInspector.redactedProviderBodyJson(...)` facade methods.
- Preserve exact redaction strings:
  - `[protected tool result redacted by prompt-debug policy]`
  - `[protected assistant answer redacted by prompt-debug policy]`
- Preserve current prompt-debug markdown structure and provider-body JSON
  formatting.
- Do not move `PromptDebugCapture`, `PromptDebugSnapshot`,
  `PromptDebugCommand`, `TraceRedactor`, `LocalTurnTraceCapture`,
  `ArtifactCanaryScanner`, `ToolContentMetadata`, or trace persistence.

Focused tests for T552:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorPrivateDocumentTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorContextLedgerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" --no-daemon
```

T552 should also add one ownership regression proving `PromptDebugInspector`
delegates redaction rather than owning provider-body traversal directly.

## Rejected Immediate Tickets

### Trace lifecycle coordinator

Rejected for now.

It would be too broad. The lifecycle crosses `TurnProcessor`,
`TurnUserRequestCapture`, `TurnAuditCapture`, `LocalTurnTraceCapture`,
`ContextLedgerCapture`, `TurnAudit`, `JsonTurnLogAppender`, and
`JsonSessionStore`.

### Move `LocalTurnTraceCapture`

Rejected for now.

The class has a wide compatibility call surface. Moving it wholesale would
create noisy changes and risk dropping trace events.

### Extract command trace events first

Rejected for T552, but plausible later.

Command trace payload extraction is coherent, but prompt-debug redaction is the
cleaner first slice because it separates artifact safety from CLI display and
is covered by targeted redaction tests.

### Move artifact canary scanning

Rejected.

The scanner is already a coherent component and is currently serving its role
as a deterministic release/audit backstop.

### Move raw artifact persistence policy

Rejected for now.

That policy is coupled to private document config, protected path handling,
model context handoff, and RAG/index decisions. It deserves a later dedicated
decision ticket if needed.

## Acceptance Criteria

- T551 makes no runtime code changes.
- Trace lifecycle ownership is documented.
- Prompt-debug lifecycle, rendering, and redaction ownership are separated.
- Trace persistence and artifact canary ownership are documented.
- Rejected immediate implementation candidates are recorded.
- The next ticket is selected as `[T552] Extract prompt-debug redaction owner`.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
