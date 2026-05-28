# [T555] Prompt-debug artifact shape decision

## Summary

T555 is a no-code inspection ticket after T554. The goal was to inspect the
post-extraction prompt-debug artifact shape before selecting the next ticket.

Decision: do not move prompt-debug capture lifecycle, trace persistence,
provider-body recording, provider-body normalization, or artifact canary
ownership next. The next coherent implementation ticket is:

```text
[T556] Extract prompt-debug destination resolver
```

## Source inspected

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 83da1839eb1f70a67b10ba33987484271fa76971
```

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/cli/prompt/PromptDebugArtifactWriter.java` | 83 | Prompt-debug artifact filenames and writes. |
| `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java` | 144 | Slash command parsing, capture selection, destination resolution, UX wording. |
| `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java` | 167 | Prompt-debug markdown display facade. |
| `src/main/java/dev/talos/cli/prompt/PromptDebugRedactor.java` | 213 | Prompt-debug message and provider-body redaction. |
| `src/main/java/dev/talos/spi/types/PromptDebugCapture.java` | 66 | Process-local latest/history capture holder. |
| `src/main/java/dev/talos/spi/types/PromptDebugSnapshot.java` | 70 | SPI prompt-debug capture value and factories. |
| `src/main/java/dev/talos/core/llm/LlmClient.java` | 1093 | Core chat request capture call sites. |
| `src/main/java/dev/talos/engine/compat/CompatChatClient.java` | 543 | Compat provider-body capture call sites. |
| `src/main/java/dev/talos/engine/ollama/OllamaChatClient.java` | 358 | Ollama provider-body capture call sites. |
| `src/test/java/dev/talos/cli/repl/slash/PromptDebugCommandTest.java` | 619 | Prompt-debug command save/render/redaction behavior. |
| `src/test/java/dev/talos/runtime/policy/ArtifactCanaryScanTest.java` | 159 | Generated artifact canary scanning. |

## Current prompt-debug counts

Broad search over `src/main/java` and `src/test/java`:

| Pattern | Count |
| --- | ---: |
| `PromptDebugArtifactWriter.writeLatest(` | 2 |
| `PromptDebugArtifactWriter.writeHistory(` | 2 |
| `PromptDebugCapture.beginTurn(` | 1 |
| `PromptDebugCapture.record(` | 33 |
| `PromptDebugCapture.latest(` | 9 |
| `PromptDebugCapture.history(` | 2 |
| `PromptDebugCapture.lastTurnHadNoProviderRequest(` | 1 |
| `PromptDebugInspector.format(` | 6 |
| `PromptDebugInspector.redactedProviderBodyJson(` | 5 |
| `PromptDebugRedactor.` | 10 |
| `PromptDebugSnapshot.fromChatRequest(` | 6 |
| `PromptDebugSnapshot.fromProviderBody(` | 20 |
| `ArtifactCanaryScanner` | 24 |
| `LocalTurnTraceCapture` | 413 |
| `TraceRedactor` | 49 |

## Post-T554 shape

### `PromptDebugArtifactWriter`

The T554 extraction is coherent. `PromptDebugArtifactWriter` now owns:

- `prompt-debug-<timestamp>.md`;
- `prompt-debug-<timestamp>.provider-body.json`;
- `prompt-debug-<timestamp>-<NN>.md`;
- `prompt-debug-<timestamp>-<NN>.provider-body.json`;
- `prompt-debug-<timestamp>-index.md`;
- `Files.createDirectories(...)`;
- `Files.writeString(...)`;
- UTF-8 artifact writes.

It stays in `dev.talos.cli.prompt` and returns data records, not CLI
`Result` values. This keeps artifact writing separate from slash-command UX.

### `PromptDebugCommand`

After T554, `PromptDebugCommand` is smaller but still owns two command-adjacent
responsibilities:

1. command UX:
   - parsing `last`, `save`, `save-all`, and `saveall`;
   - selecting latest/history captures;
   - missing-capture messages;
   - final `Result` wording;
   - help text.
2. destination resolution:
   - explicit save directory;
   - `talos.promptDebugDir`;
   - `TALOS_PROMPT_DEBUG_DIR`;
   - default `~/.talos/prompt-debug`;
   - optional quote stripping;
   - absolute normalization.

The command UX belongs in `PromptDebugCommand`. Destination resolution is
artifact policy, not command rendering. It is the cleanest remaining narrow
prompt-debug implementation slice.

### `PromptDebugInspector` and `PromptDebugRedactor`

`PromptDebugInspector` is now a display facade. It formats:

- summary header fields;
- task-contract target coverage;
- context ledger summary;
- structured messages;
- provider-body display section.

`PromptDebugRedactor` owns protected/private prompt-debug redaction mechanics.
It still depends on `ProtectedContentPolicy` and `TraceRedactor`. That is
acceptable for the current lane because prompt-debug artifact safety is the
redactor's purpose. Do not split this further until there is a broader
redaction-policy decision across prompt-debug, trace, session, and provider-body
artifacts.

### `PromptDebugCapture` and `PromptDebugSnapshot`

Do not move these next.

`PromptDebugCapture.beginTurn()` has a small production call-site count, but the
record/latest/history behavior is lifecycle-sensitive:

- latest user-facing capture;
- latest recorded capture;
- user-facing history;
- background maintenance filtering;
- no-provider-turn state.

`PromptDebugSnapshot` factories are called from core and engine/provider
adapters. Moving them now would cross SPI, core, engine, and prompt-debug
semantics at once. That is not a narrow T556.

### Provider-body recording

Do not normalize provider-body recording next.

Provider-body capture call sites are distributed across:

- `LlmClient`;
- `CompatChatClient`;
- `OllamaChatClient`;
- provider-specific retry and streaming paths.

That work is real, but it is not a post-T554 artifact-shape cleanup. It should
be a later provider-capture design ticket if source inspection shows enough
duplication and stable semantics.

### Artifact canary ownership

Do not move artifact canary ownership next.

`ArtifactCanaryScanner` is broader than prompt-debug. It scans prompt-debug,
provider bodies, sessions, traces, turns, command output, reports, build
outputs, and manual audit roots. Moving it in the prompt-debug lane would mix a
release-gate scanner with one CLI maintainer command.

## Rejected next tickets

### Move prompt-debug capture lifecycle

Rejected for now. The lifecycle mixes current-turn reset, user-facing capture
filtering, recorded capture history, background maintenance exclusion, and
runtime-owned no-provider-turn reporting.

### Move prompt-debug snapshot factories

Rejected for now. Snapshot factories are the SPI bridge between core request
capture and engine/provider body capture. A bad move here would create a worse
dependency boundary.

### Normalize provider-body capture

Rejected for now. There are multiple provider paths and retry/streaming paths.
This should be inspected as a separate provider-capture lane, not slipped into
the artifact writer lane.

### Move artifact canary scanner

Rejected for now. The scanner is a release/runtime artifact safety gate, not
prompt-debug-specific code.

### Close the prompt-debug lane now

Rejected. `PromptDebugCommand` still owns destination resolution policy. That
is a small, testable, coherent owner and should be extracted before closing the
lane.

## Selected next ticket

```text
[T556] Extract prompt-debug destination resolver
```

Implementation shape:

- Create `dev.talos.cli.prompt.PromptDebugDestinationResolver`.
- Move only destination precedence and optional quote stripping out of
  `PromptDebugCommand`.
- Keep `PromptDebugCommand` responsible for parsing, capture selection,
  missing-capture UX, help text, and `Result` wording.
- Keep `PromptDebugArtifactWriter` responsible only for filenames and writes.
- Preserve precedence exactly:
  1. explicit directory;
  2. `talos.promptDebugDir`;
  3. `TALOS_PROMPT_DEBUG_DIR`;
  4. `~/.talos/prompt-debug`.
- Preserve absolute path normalization.
- Preserve quoted explicit directory behavior.
- Add an ownership regression proving `PromptDebugCommand` delegates destination
  resolution and no longer owns `talos.promptDebugDir`,
  `TALOS_PROMPT_DEBUG_DIR`, or quote stripping.

Focused tests for T556:

```powershell
.\gradlew.bat test `
  --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" `
  --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" `
  --no-daemon
```

T556 should also include the standard local gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance criteria

- Post-T554 prompt-debug artifact shape is documented from source evidence.
- The next ticket is selected from the current source shape.
- No code changes are made in T555.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are included.
