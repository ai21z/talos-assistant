# [T451-done-high] Extract Terminal Read-Only Stop Answer

## Status

Done.

## Scope

T451 implements the T450 decision: extract deterministic terminal read-only
stop-answer selection from `ToolCallRepromptStage`.

This is an ownership refactor. It preserves runtime behavior and does not
change terminal answer wording, tool selection, diagnostics, unsupported
document handling, evidence containment, context-budget continuation, mutation
repair, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `d9b21464`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ToolCallRepromptStage.java` after extraction | 2436 lines |
| `TerminalReadOnlyStopAnswer.java` | 232 lines |
| Architecture baseline | 0 |

## Change

Added:

```text
dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer
```

`TerminalReadOnlyStopAnswer` now owns deterministic answer selection for:

- read-only static web diagnostics;
- unsupported binary document capability notes;
- directory listing terminal answers;
- single-target read terminal answers;
- converted text fallback suppression for unsupported document targets;
- alias-aware successful tool-result body selection for these stop answers;
- static web read-surface checks for terminal diagnostic answers.

`ToolCallRepromptStage` keeps lifecycle placement:

- ask the owner whether a terminal read-only answer applies;
- set `LoopState.currentText`;
- clear `LoopState.currentNativeCalls`;
- preserve the existing debug log message for the chosen stop answer;
- stop the tool loop.

## Guardrails

Preserved:

- `Read <target>:` answer wording;
- `Directory entries:` answer rendering;
- unsupported binary document capability note wording;
- converted text fallback suppression;
- read-only static web diagnostic rendering;
- exclusion of workspace-explain retry-wrapped prompts from web diagnostics;
- static-web surface requirement;
- duplicate read-result suppression;
- alias handling for read/list tool names;
- existing `ToolCallRepromptStage` call order.

Not changed:

- compact mutation continuation;
- compact read-only evidence continuation;
- context-budget failure dominance;
- mutation repair;
- expected-target repair;
- source-evidence repair;
- final outcome warning construction;
- `AssistantTurnExecutor` read-only diagnostic follow-up behavior.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswerTest" --no-daemon
```

Expected compile failure:

```text
cannot find symbol
  symbol:   variable TerminalReadOnlyStopAnswer
```

GREEN focused verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswerTest" --no-daemon
```

Adjacent behavior verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswerTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.modes.UnsupportedFinalAnswerTruthfulnessTest" --tests "dev.talos.cli.modes.ReadEvidenceHandoffTest" --no-daemon
```

## Full Verification

Run before merge:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
