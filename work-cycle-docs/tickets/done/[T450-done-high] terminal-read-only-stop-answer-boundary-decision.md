# [T450-done-high] Terminal Read-Only Stop-Answer Boundary Decision

## Status

Done.

## Scope

T450 inspects whether the terminal read-only stop answers in
`ToolCallRepromptStage` form a coherent ownership unit after the context-budget
continuation lane was closed by T449.

This is a no-code decision ticket. It does not change runtime behavior,
terminal wording, tool selection, diagnostics, unsupported-document handling,
or evidence containment.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `05ff0aed`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ToolCallRepromptStage.java` | 2621 lines |
| Architecture baseline | 0 |

## Source Inventory

`ToolCallRepromptStage.reprompt(...)` currently checks several deterministic
terminal read-only answers before generic post-iteration policy:

- `readOnlyWebDiagnosticStopAnswer(...)`;
- `unsupportedDocumentStopAnswer(...)`;
- `directoryListingStopAnswer(...)`;
- `readTargetStopAnswer(...)`.

These methods share a real role:

- decide whether a read-only/tool-policy loop has enough runtime-owned evidence
  to stop without another model turn;
- synthesize deterministic answer text from already gathered evidence;
- clear native tool calls by returning terminal text to the reprompt lifecycle;
- prevent unsupported or ungrounded model prose from becoming the final answer.

They are not context-budget continuation behavior, mutation repair behavior, or
generic failure-policy dominance.

## Couplings

The boundary is still runtime/toolcall-local, not CLI-owned:

- `readTargetStopAnswer(...)` reads the current `TaskContract` and checks
  successful `talos.read_file` evidence for the single expected target.
- `directoryListingStopAnswer(...)` delegates selection to
  `DirectoryListingEvidence` and renders deterministic directory entries.
- `unsupportedDocumentStopAnswer(...)` uses unsupported read paths from the
  current iteration and suppresses the stop answer when the user explicitly
  named a converted text fallback.
- `readOnlyWebDiagnosticStopAnswer(...)` uses read-only static-web intent,
  read surface checks, and `StaticTaskVerifier.renderWebDiagnostics(...)`.
- helper logic still includes alias resolution, tool-result body parsing,
  filename-stem matching, task-type declaration checks, and static-web surface
  detection.

These dependencies are acceptable for a runtime/toolcall owner, but too mixed
for a generic outcome or CLI-mode package.

## Decision

Extracting the terminal read-only stop answers is a coherent next
implementation ticket.

Do not move them in T450. The next ticket should perform one focused
behavior-preserving extraction behind the current `ToolCallRepromptStage`
facade.

Target owner:

```text
dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer
```

Target API shape should stay simple:

```text
TerminalReadOnlyStopAnswer.tryAnswer(LoopState state, ToolCallExecutionStage.IterationOutcome outcome)
```

It should return the exact terminal answer text when one applies, or `null` /
empty optional when it does not. `ToolCallRepromptStage` should keep lifecycle
placement: call the owner, set `currentText`, clear `currentNativeCalls`, log,
and stop the loop.

## T451 Guardrails

T451 must preserve exact behavior and wording for:

- read-target stop answers such as `Read config.json:`;
- directory listing rendering such as `Directory entries:`;
- unsupported binary document capability notes;
- converted text fallback suppression for unsupported document targets;
- read-only static web diagnostics output;
- exclusion of workspace-explain retry-wrapped prompts from web diagnostics;
- static web surface requirement that both HTML and script files were read;
- alias handling for `read_file`, `talos.read_file`, `list_dir`, and
  `talos.list_dir`;
- stale duplicate read-result suppression.

T451 must not touch:

- compact mutation continuation;
- compact read-only evidence continuation;
- context-budget failure dominance;
- mutation repair;
- expected-target repair;
- source-evidence repair;
- final outcome warning construction;
- `AssistantTurnExecutor` read-only diagnostic follow-up behavior.

## Suggested T451 Verification

Focused owner tests should cover:

- directory listing stop answer;
- single-target read stop answer with alias handling and duplicate-read
  suppression;
- unsupported document stop answer;
- converted text fallback suppression;
- read-only web diagnostics stop answer;
- mutation/web-fix requests do not use the read-only web diagnostic stop.

Adjacent regression tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*Directory*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*Unsupported*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*ReadOnlyWebDiagnostics*" --no-daemon
```

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
