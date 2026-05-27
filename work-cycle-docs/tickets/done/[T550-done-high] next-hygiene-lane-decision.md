# [T550-done-high] Next Hygiene Lane Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T550`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `d8699ec0`
Predecessor: `T549`

## Scope

T550 is a no-code inspection and decision ticket.

T549 closed the tool-loop outcome value lane and left three possible next
hygiene lanes:

1. runtime/CLI boundary review for `AssistantTurnExecutor`;
2. trace and artifact evidence ownership review;
3. test-fixture construction hygiene.

T550 inspects current source before selecting the next lane. It intentionally
does not implement another extraction.

## Source Inspection Commands

```powershell
git status --short --branch
git rev-parse --short HEAD
git rev-parse --short origin/v0.9.0-beta-dev

rg -n "^(\\s*)public static|^(\\s*)private static|class Bag|ThreadLocal|complete\\(|clear\\(|ContextLedgerCapture|recordPromptAudit|recordOutcome|recordWarning|record.*Artifact|rawArtifactPersistenceAllowed|saveTrace|loadLatestTrace" `
  src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java `
  src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java `
  src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java `
  src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java `
  src/main/java/dev/talos/runtime/SessionStore.java `
  src/main/java/dev/talos/tools

rg -n "LocalTurnTraceCapture\\." src/main/java src/test/java src/e2eTest/java |
  Group-Object { ($_ -split ':')[0] } |
  Sort-Object Count -Descending |
  Select-Object -First 60 Count,Name

rg -n "PromptDebugCapture|PromptDebugInspector|prompt-debug|provider-body|PromptAuditSnapshot|saveTrace|loadLatestTrace|ArtifactCanaryScanner|rawArtifactPersistenceAllowed|ToolContentMetadata" `
  src/main/java src/test/java src/e2eTest/java |
  Group-Object { ($_ -split ':')[0] } |
  Sort-Object Count -Descending |
  Select-Object -First 70 Count,Name
```

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `d8699ec0`:

| Source | Lines | Current role |
| --- | ---: | --- |
| `AssistantTurnExecutor.java` | 3191 | CLI-mode turn orchestration, prompt audit wiring, direct answers, final-answer shaping, static-web diagnostics, truthfulness annotations. |
| `TurnProcessor.java` | 1196 | Runtime turn lifecycle, trace lifecycle start/complete/clear, tool execution, approval/checkpoint policy sequencing. |
| `LocalTurnTraceCapture.java` | 619 | Thread-local trace builder, event vocabulary, context ledger bridge, outcome/warning/repair/verification recorder. |
| `LocalTurnTrace.java` | 368 | Local trace artifact value and builder. |
| `PromptDebugInspector.java` | 364 | Maintainer prompt-debug formatter and provider-body redactor. |
| `JsonSessionStore.java` | 519 | Session, turn, and trace artifact persistence with text-node sanitization. |
| `ToolResultModelContextHandoff.java` | 243 | Protected/private tool-result model-context handoff and handoff trace events. |
| `ArtifactCanaryScanner.java` | 130 | Deterministic generated-artifact canary scanner. |
| `TurnAuditCapture.java` | 131 | Thread-local turn audit collector and local trace bridge. |
| `PromptDebugCapture.java` | 66 | Process-local latest prompt-debug snapshot/history holder. |

Reference counts from source search:

| Surface | Files | Matching lines |
| --- | ---: | ---: |
| `LocalTurnTraceCapture.` | 42 | 388 |
| `PromptDebugCapture` | 14 | 80 |
| `PromptDebugInspector` | 7 | 23 |
| `ArtifactCanaryScanner` | 8 | 46 |
| `saveTrace(...)` / `loadLatestTrace(...)` | 7 | 13 |
| `ToolContentMetadata` | 14 | 72 |
| `rawArtifactPersistenceAllowed` | 10 | 20 |

## Findings

### `AssistantTurnExecutor` Is Still Broad, But Not The Next Direct Target

`AssistantTurnExecutor` remains a large concentration point. Current inspection
shows it still coordinates:

- prompt-debug turn start;
- current-turn plan and prompt audit recording;
- backend failure outcome recording;
- deterministic direct answers;
- repair planning trace entries;
- tool-loop answer resolution;
- answer shaping after tool loops;
- no-tool truthfulness annotations;
- read-only and static-web diagnostic helpers.

That is real architectural debt.

Starting the next ticket by extracting a random `AssistantTurnExecutor` helper
would be wrong. The remaining responsibilities are mixed orchestration,
truthfulness wording, runtime evidence, static-web diagnostics, CLI answer
formatting, and legacy compatibility. A direct implementation ticket here
would risk recreating another vague answer-shaping warehouse.

### Test-Fixture Construction Noise Is Real, But Not The Next Release-Critical Lane

T549 measured broad direct construction of `ToolCallLoop.LoopResult` and
`ToolCallLoop.ToolOutcome`. T550 reinspection confirms this remains mostly
test-construction and compatibility surface churn.

That work can become useful later, especially before a deliberate value-model
migration. It is not the best next lane now because it does not improve the
runtime trust boundary, evidence quality, prompt-debug safety, or audit
truthfulness as directly as the trace/artifact lane.

### Trace And Artifact Evidence Ownership Is The Correct Next Lane

Trace and artifact evidence is a product doctrine boundary, not just another
class-size problem.

The project doctrine says final answers are the least trusted artifact and must
be judged against source code, tests, tool results, approval records, command
output, verifier output, local traces, prompt-debug artifacts, provider-body
captures, logs, diffs, and final workspace state. The current source shows that
this evidence surface is implemented across several separate mechanisms:

| Current owner | Evidence responsibility |
| --- | --- |
| `TurnProcessor` | starts/completes/clears `LocalTurnTraceCapture`; embeds completed trace in `TurnAudit`. |
| `LocalTurnTraceCapture` | owns thread-local trace event recording, event vocabulary, outcome/warning/repair/verification summaries, context ledger bridge. |
| `TurnAuditCapture` | records tool-call summaries and mirrors selected events into local trace. |
| `AssistantTurnExecutor` | begins prompt-debug turn capture and records prompt audit snapshots into local trace. |
| `PromptDebugCapture` | stores latest user-facing and recorded provider/request prompt-debug snapshots. |
| `PromptDebugInspector` | formats prompt-debug evidence and redacts provider-body/message content. |
| `JsonSessionStore` / `SessionStore` | persists and loads redacted local trace artifacts. |
| `JsonTurnLogAppender` | saves completed local trace artifacts from `TurnAudit`. |
| `ToolResultModelContextHandoff` | records protected/private document handoff approvals and context inclusion decisions. |
| `ToolContentMetadata` | carries model-handoff and raw-artifact persistence facts. |
| `ArtifactCanaryScanner` | scans generated artifacts for raw privacy canaries. |

This is coherent enough to work, but not yet coherent enough to be called a
settled ownership model. The next lane should decide the boundary before
extracting anything.

## Decision

The next hygiene lane is trace and artifact evidence ownership.

Do not start by moving `LoopResult`, `ToolOutcome`, or test fixture builders.

Do not start by extracting another random `AssistantTurnExecutor` helper.

Do not start by moving `LocalTurnTraceCapture` wholesale. It is a broad static
thread-local recorder with 42 source/test/e2e reference files and 388 matching
call lines. A casual move would be compatibility churn and could weaken trace
coverage.

Start with a decision/inventory ticket:

```text
[T551] Trace And Artifact Evidence Ownership Decision
```

## T551 Questions

T551 should inspect the trace/artifact evidence surface and answer:

1. Which component owns the turn trace lifecycle: begin, complete, clear, and
   context-ledger coupling?
2. Which component owns prompt-debug lifecycle versus prompt-debug rendering?
3. Which component owns provider-body redaction and protected/private document
   message redaction?
4. Which component owns local trace event vocabulary, and which call sites
   should only publish typed events?
5. Which evidence records must remain process-local or thread-local for beta
   compatibility?
6. Which artifacts are allowed to persist raw content, redacted content, hashes,
   summaries, or no content?
7. Which canary scans are release gates, developer gates, or audit-only checks?
8. Whether the next implementation ticket should extract a small owner such as
   a trace lifecycle coordinator, prompt-debug evidence service, artifact
   persistence policy, or typed event sink.

## Rejected Immediate Tickets

### Move `LocalTurnTraceCapture`

Rejected for now.

It is not one isolated behavior. It is a thread-local trace facade, event
vocabulary, builder adapter, context-ledger bridge, and compatibility call
surface for runtime, CLI, tests, and E2E harnesses.

### Extract prompt-debug formatting immediately

Rejected for now.

`PromptDebugInspector` mixes maintainer display formatting, provider-body JSON
redaction, protected-path parity, private-document redaction, and context-ledger
display. A ticket can extract from it later, but only after T551 decides whether
prompt-debug is CLI maintainer UI, runtime evidence, or a split of both.

### Start an `AssistantTurnExecutor` extraction

Rejected for now.

The file is still too broad, but the trace/prompt-debug/evidence concerns are
one of the most release-relevant reasons it remains broad. Decide that boundary
first.

### Rewrite tool-loop value tests

Rejected for now.

Useful later, but weaker than trace/artifact ownership for release trust.

## Acceptance Criteria

- T550 makes no runtime code changes.
- Current source evidence is recorded.
- The next hygiene lane is selected from source inspection.
- Immediate rejected implementation tickets are documented.
- The next ticket is identified as `[T551] Trace And Artifact Evidence Ownership Decision`.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
