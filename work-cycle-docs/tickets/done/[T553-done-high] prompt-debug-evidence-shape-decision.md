# [T553-done-high] Prompt-Debug Evidence Shape Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T553`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `72fa4a6f`
Predecessor: `T552`

## Scope

T553 is a no-code inspection and decision ticket.

It inspects the post-T552 prompt-debug evidence shape before selecting the
next implementation ticket. It intentionally does not move prompt-debug capture
lifecycle, trace persistence, or trace capture.

## Source Inspection

Commands used:

```powershell
git status --short --branch
git rev-parse --short HEAD
git rev-parse --short origin/v0.9.0-beta-dev

rg -n "PromptDebugCapture|PromptDebugSnapshot|PromptDebugInspector|PromptDebugRedactor|prompt-debug|promptDebug|providerBodyJson|redactedProviderBodyJson|ContextLedgerCapture|PromptAuditSnapshot|recordPromptAudit|LocalTurnTraceCapture|ArtifactCanaryScanner" `
  src/main/java src/test/java src/e2eTest/java work-cycle-docs/tickets/done

rg -n "PromptDebugCapture\\.beginTurn\\(|PromptDebugCapture\\.record\\(|PromptDebugCapture\\.latest\\(|PromptDebugCapture\\.history\\(|PromptDebugInspector\\.format\\(|PromptDebugInspector\\.redactedProviderBodyJson\\(" `
  src/main/java src/test/java src/e2eTest/java

rg -n "fromProviderBody\\(|fromChatRequest\\(" src/main/java src/test/java src/e2eTest/java
```

## Current Shape

Measured from fresh `origin/v0.9.0-beta-dev` at `72fa4a6f`:

| Source | Lines | Current role |
| --- | ---: | --- |
| `PromptDebugInspector` | 191 | Prompt-debug maintainer display facade: task contract summary, target coverage, context ledger section, structured message rendering, provider-body section wiring. |
| `PromptDebugRedactor` | 233 | Prompt-debug message/provider-body redaction owner: protected tool result IDs, protected assistant answer redaction, provider-body JSON traversal, fallback text redaction, sanitizer pass. |
| `PromptDebugCommand` | 189 | Hidden slash command, help text, capture selection, destination precedence, artifact file naming/writes, history index, and user-facing save messages. |
| `PromptDebugCapture` | 78 | SPI process-local latest/history holder and background-maintenance filter. |
| `PromptDebugSnapshot` | 76 | SPI capture value and factories for chat-request and provider-body shapes. |
| `LlmClient` | 1206 | Core LLM client; records chat-request prompt-debug snapshots. |
| `CompatChatClient` | 619 | Compat transport; records provider-body prompt-debug snapshots. |
| `OllamaChatClient` | 416 | Ollama transport; records provider-body prompt-debug snapshots. |
| `SynchronizedApprovalAuditRunner` | 762 | E2E audit harness; writes prompt-debug/provider-body artifacts from captured snapshots. |

Prompt-debug call-site counts across main/test/e2e sources:

| Pattern | Count |
| --- | ---: |
| `PromptDebugCapture.beginTurn(` | 2 |
| `PromptDebugCapture.record(` | 33 |
| `PromptDebugCapture.latest(` | 10 |
| `PromptDebugCapture.history(` | 2 |
| `PromptDebugInspector.format(` | 7 |
| `PromptDebugInspector.redactedProviderBodyJson(` | 6 |
| `PromptDebugSnapshot.fromChatRequest(` | 6 |
| `PromptDebugSnapshot.fromProviderBody(` | 20 |

The capture side is broad. The artifact-writing side is much narrower.

## Ownership Decisions

### `PromptDebugInspector`

Decision: keep it as the display facade.

After T552, it no longer owns provider-body traversal or protected/private
redaction mechanics. It still composes useful maintainer output:

- capture header;
- task contract;
- expected/evidence target coverage;
- exact-literal coverage;
- context ledger summary;
- structured messages;
- provider-body section.

This is a coherent display owner. Splitting context ledger display next would
be small, but not the most important remaining evidence-ownership issue.

### `PromptDebugRedactor`

Decision: leave it as the redaction owner for now.

It owns the correct extracted slice from T552. It is not a general runtime
redactor. It is prompt-debug artifact safety, so its CLI prompt package
ownership is acceptable.

Do not broaden it into trace redaction or session artifact redaction.

### `PromptDebugCapture` / `PromptDebugSnapshot`

Decision: do not move capture lifecycle next.

The capture holder is in SPI because core clients and engine adapters record
snapshots from different layers. Capture producers are spread across
`LlmClient`, `CompatChatClient`, `OllamaChatClient`, tests, and audit harnesses.

Moving lifecycle or factories now would be broad and risk stale prompt-debug
state, background-maintenance filtering, and no-provider-turn reporting.

### Provider Request Producers

Decision: do not normalize provider request recording next.

There are two valid capture shapes:

- `fromChatRequest(...)` for core request shape before transport conversion;
- `fromProviderBody(...)` for actual HTTP/provider body.

Both are legitimate evidence. Collapsing them would be a design change, not a
small hygiene extraction.

### Trace Persistence And Local Trace Capture

Decision: do not touch trace persistence or `LocalTurnTraceCapture`.

Prompt-debug evidence artifacts are adjacent to local trace evidence, but they
are not the same owner. T553 found no source evidence that trace persistence is
the next clean slice.

### Prompt-Debug Artifact Writing

Decision: this is the next clean implementation slice.

`PromptDebugCommand` currently owns too many artifact concerns:

- slash-command parsing;
- hidden command help;
- latest/history capture selection;
- destination precedence;
- timestamped file naming;
- markdown/provider-body JSON writes;
- history index writes;
- user-facing save result text.

The command should own parsing, destination precedence, missing-capture UX, and
final `Result` construction. A prompt-debug artifact writer should own file
naming and file writes for latest/history snapshots.

This is narrower and safer than capture lifecycle. It also directly improves
the trace/artifact evidence ownership lane.

## Next Implementation Ticket

The next implementation ticket should be:

```text
[T554] Extract prompt-debug artifact writer
```

Proposed implementation shape:

- Create package-private `dev.talos.cli.prompt.PromptDebugArtifactWriter`.
- Move timestamped prompt-debug artifact file naming and `Files.writeString`
  operations out of `PromptDebugCommand`.
- Keep destination precedence in `PromptDebugCommand`:
  1. explicit directory;
  2. `talos.promptDebugDir`;
  3. `TALOS_PROMPT_DEBUG_DIR`;
  4. `~/.talos/prompt-debug`.
- Keep command parsing and user-facing `Result` text in `PromptDebugCommand`.
- Keep `PromptDebugInspector.format(...)` and
  `PromptDebugInspector.redactedProviderBodyJson(...)` as the rendering/redaction
  facade used by the artifact writer.
- Preserve exact filenames and output wording:
  - `prompt-debug-<timestamp>.md`;
  - `prompt-debug-<timestamp>.provider-body.json`;
  - `prompt-debug-<timestamp>-<NN>.md`;
  - `prompt-debug-<timestamp>-<NN>.provider-body.json`;
  - `prompt-debug-<timestamp>-index.md`;
  - `Saved prompt debug render to:`;
  - `Saved provider body JSON to:`;
  - `Saved prompt debug history index to:`.
- Add an ownership regression proving `PromptDebugCommand` delegates artifact
  writing rather than directly calling `Files.writeString`.

Focused tests for T554:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" --no-daemon
```

Run them in one Gradle invocation if needed to avoid parallel writes to the
same Jacoco/test-result outputs.

## Rejected Immediate Tickets

### Move prompt-debug capture lifecycle

Rejected.

`PromptDebugCapture.beginTurn()` is started by `AssistantTurnExecutor` and the
synchronized approval audit harness. `PromptDebugCapture.record(...)` is called
by core and engine transport layers. This is not a one-owner extraction.

### Move prompt-debug snapshot factories

Rejected.

The factories encode real evidence distinctions between chat-request shape and
provider-body shape. Moving them without a broader evidence model would add
indirection without improving correctness.

### Move trace persistence

Rejected.

Trace persistence is a separate lane involving `SessionStore`,
`JsonSessionStore`, `JsonTurnLogAppender`, and local trace lifecycle.

### Extract context ledger display first

Rejected for now.

It is possible, but lower value than artifact writing. `PromptDebugInspector`
is now a coherent display facade, while `PromptDebugCommand` still mixes
command UX and artifact write mechanics.

## Acceptance Criteria

- T553 makes no runtime code changes.
- Post-T552 prompt-debug ownership is documented from source inspection.
- Capture lifecycle, provider recording, trace persistence, and context-ledger
  display are explicitly rejected as immediate implementation tickets.
- The next ticket is selected as `[T554] Extract prompt-debug artifact writer`.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
