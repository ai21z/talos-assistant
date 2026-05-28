# [T557] Prompt-debug command/artifact lane closeout

## Summary

T557 is a no-code inspection ticket after T556. It inspects the prompt-debug
command/artifact shape after destination resolution moved out of
`PromptDebugCommand`.

Decision: close the prompt-debug command/artifact sublane for now. Do not start
another prompt-debug extraction unless a later source inspection proves a
specific owner. The next ticket should return to the broader trace/artifact
evidence lane and inspect local trace evidence ownership before implementation.

```text
[T558] Local trace evidence ownership decision
```

## Source inspected

Fresh beta base:

```text
origin/v0.9.0-beta-dev = ca2a7916
```

Primary files inspected:

| File | Current owner |
| --- | --- |
| `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java` | Hidden slash-command UX, capture selection, missing-capture wording, final save result wording, help text. |
| `src/main/java/dev/talos/cli/prompt/PromptDebugDestinationResolver.java` | Prompt-debug destination precedence, quote handling, absolute normalization. |
| `src/main/java/dev/talos/cli/prompt/PromptDebugArtifactWriter.java` | Timestamped prompt-debug filenames, markdown/provider-body writes, save-all index writes. |
| `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java` | Prompt-debug maintainer display facade. |
| `src/main/java/dev/talos/cli/prompt/PromptDebugRedactor.java` | Prompt-debug message/provider-body artifact redaction. |
| `src/main/java/dev/talos/spi/types/PromptDebugCapture.java` | Process-local latest/history capture holder and background-maintenance filter. |
| `src/main/java/dev/talos/spi/types/PromptDebugSnapshot.java` | SPI prompt-debug capture value and chat-request/provider-body factories. |
| `src/main/java/dev/talos/core/llm/LlmClient.java` | Core chat-request prompt-debug capture call sites. |
| `src/main/java/dev/talos/engine/compat/CompatChatClient.java` | OpenAI-compatible provider-body capture call sites. |
| `src/main/java/dev/talos/engine/ollama/OllamaChatClient.java` | Ollama provider-body capture call sites. |
| `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java` | Broad generated-artifact canary scanner. |

## Current measurements

Broad source/test search over `src/main/java` and `src/test/java`:

| Pattern | Count |
| --- | ---: |
| `PromptDebugCapture.record(` | 33 |
| `PromptDebugSnapshot.fromChatRequest(` | 5 |
| `PromptDebugSnapshot.fromProviderBody(` | 18 |
| `PromptDebugCapture.beginTurn(` | 1 |
| `PromptDebugCapture.history(` | 2 |
| `PromptDebugCapture.latest(` | 9 |
| `PromptDebugCapture.latestRecorded(` | 3 |
| `PromptDebugCapture.lastTurnHadNoProviderRequest(` | 1 |
| `PromptDebugInspector.format(` | 6 |
| `PromptDebugInspector.redactedProviderBodyJson(` | 5 |
| `PromptDebugRedactor.` | 10 |
| `PromptDebugArtifactWriter.writeLatest(` | 2 |
| `PromptDebugArtifactWriter.writeHistory(` | 2 |
| `PromptDebugDestinationResolver.resolve(` | 9 |
| `ArtifactCanaryScanner` | 24 |
| `LocalTurnTraceCapture` | 413 |
| `TraceRedactor` | 49 |

## Post-T556 ownership shape

### `PromptDebugCommand`

`PromptDebugCommand` is now mostly a command facade. It owns command parsing,
hidden help text, capture selection, missing-capture wording, and final
user-facing save output.

That is a coherent command owner. Moving final result text out now would be a
low-value split: the text is CLI UX, not artifact policy.

### `PromptDebugDestinationResolver`

`PromptDebugDestinationResolver` owns the destination policy selected in T556:
explicit directory, system property, environment variable, default home
directory, optional quote stripping, and normalization.

This slice is complete. Do not move it again.

### `PromptDebugArtifactWriter`

`PromptDebugArtifactWriter` owns artifact filenames and writes. It returns data
records and does not import CLI `Result` types. That boundary is still correct.

### `PromptDebugInspector` and `PromptDebugRedactor`

`PromptDebugInspector` is a display facade. `PromptDebugRedactor` owns protected
tool result, protected assistant answer, private document, provider-body JSON,
and fallback text redaction mechanics for prompt-debug artifacts.

This split is coherent for beta. Do not broaden `PromptDebugRedactor` into a
general trace/session redactor in the prompt-debug lane.

### `PromptDebugCapture` and `PromptDebugSnapshot`

Do not move these next. The capture side is broader than the command/artifact
side:

- `PromptDebugCapture.beginTurn()` is runtime turn lifecycle state.
- `PromptDebugCapture.record(...)` is called from core and engine transport
  layers.
- `PromptDebugSnapshot` factories preserve two real evidence shapes:
  chat-request shape and provider-body shape.

Moving them now would mix SPI compatibility, turn lifecycle, provider adapters,
background-maintenance filtering, and no-provider-turn reporting.

### Provider-body capture producers

Do not normalize provider-body capture next. The current call sites record
actual transport JSON from `CompatChatClient` and `OllamaChatClient`, while
`LlmClient` records core chat-request shape before transport conversion.

Those are not duplicate responsibilities. They are different evidence layers.
Any provider-capture redesign should be a dedicated decision ticket, not a
prompt-debug command cleanup.

### `ArtifactCanaryScanner`

Do not move artifact canary ownership next. It scans prompt-debug,
provider-body, trace, session, turn, command-output, report, and manual audit
artifacts. It is broader than prompt-debug and already acts as a deterministic
release/audit backstop.

## Rejected next tickets

### Extract another `PromptDebugCommand` formatter

Rejected. The remaining output text is command UX and is already small.

### Move prompt-debug capture lifecycle

Rejected. It crosses runtime turn start, process-local state, latest/history
semantics, background-maintenance filtering, and no-provider-turn reporting.

### Normalize provider-body recording

Rejected. Provider-body recording spans core request shape and transport body
shape. A bad extraction would blur evidence layers instead of clarifying them.

### Move artifact canary scanner

Rejected. The scanner is not prompt-debug-specific.

### Start trace persistence implementation

Rejected for now. Trace persistence touches session store, turn logs, trace
redaction, and runtime completion timing. It needs a fresh decision pass before
implementation.

## Decision

The prompt-debug command/artifact lane is closed for now.

The next correct ticket is a no-code decision/inventory ticket:

```text
[T558] Local trace evidence ownership decision
```

T558 should inspect `LocalTurnTraceCapture`, `LocalTurnTrace`,
`TurnTraceEvent`, `TraceRedactor`, `PromptAuditSnapshot`, `TurnProcessor`,
`TurnAuditCapture`, `JsonTurnLogAppender`, and `JsonSessionStore` before
choosing any implementation.

T558 should answer:

1. which owner controls trace lifecycle start/complete/clear;
2. which owner controls trace event vocabulary;
3. which event families are coherent enough to extract behind the existing
   facade;
4. which redaction/sanitization behavior belongs to trace, prompt-debug,
   session persistence, or artifact canary scanning;
5. whether the next implementation ticket is an event-family extraction,
   persistence-boundary extraction, redaction-boundary extraction, or no code.

## Acceptance criteria

- T557 makes no runtime code changes.
- Post-T556 prompt-debug command/artifact ownership is documented from source.
- Capture lifecycle, provider-body normalization, artifact canary movement, and
  trace persistence implementation are explicitly rejected as immediate moves.
- The next ticket is selected as `[T558] Local trace evidence ownership
  decision`.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
