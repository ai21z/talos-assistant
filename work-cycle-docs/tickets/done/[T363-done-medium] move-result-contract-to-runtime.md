# [T363-done-medium] Move Result Contract To Runtime

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T363`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T362-done-medium] move-active-task-context-updater-to-cli-memory`

## Evidence Summary

- Source: post-T362 implementation after PR #27 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `016f49ebffbbe50d64b8294bd16be75d9ad8254d`.
- Beta push CI: run `#77`, `Beta Dev CI`, push event for `016f49e`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T363`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - moved the renderable result contract from `dev.talos.cli.repl.Result` to
    `dev.talos.runtime.Result`;
  - updated CLI mode, REPL, slash-command, runtime, and test imports;
  - kept terminal rendering in `dev.talos.cli.repl.RenderEngine`;
  - removed four stale runtime-to-CLI baseline entries.
- Verification status: passed.

## Problem

After T362, runtime still imported the CLI-owned `Result` type from four
runtime classes:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/JsonTurnLogAppender.java|dev.talos.cli.repl.Result
runtime-core-no-cli|src/main/java/dev/talos/runtime/MemoryUpdateListener.java|dev.talos.cli.repl.Result
runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnProcessor.java|dev.talos.cli.repl.Result
runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnResult.java|dev.talos.cli.repl.Result
```

That package ownership was false. `Result` is not a terminal adapter. It is the
shared output contract carried by runtime turn processing, session listeners,
mode dispatch, slash-command execution, and CLI rendering.

Keeping it under `dev.talos.cli.repl` made runtime depend upward on CLI for a
contract that runtime itself emits, audits, and persists.

## Change

T363 moves:

```text
dev.talos.cli.repl.Result
```

to:

```text
dev.talos.runtime.Result
```

This keeps ownership aligned:

- runtime owns the result contract and turn metadata;
- CLI modes and slash commands may create runtime results;
- CLI `RenderEngine` remains the terminal adapter that renders those results;
- runtime listeners can extract, classify, and persist result text without
  importing `dev.talos.cli`.

The change is package relocation only. It does not rename the result variants,
change rendering behavior, or change turn-processing semantics.

## Baseline Result

Architecture baseline moved:

```text
34 -> 30
```

Removed entries:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/JsonTurnLogAppender.java|dev.talos.cli.repl.Result
runtime-core-no-cli|src/main/java/dev/talos/runtime/MemoryUpdateListener.java|dev.talos.cli.repl.Result
runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnProcessor.java|dev.talos.cli.repl.Result
runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnResult.java|dev.talos.cli.repl.Result
```

## Tests Updated

No behavior tests needed semantic changes. Imports were updated where tests
construct or inspect `Result` values.

Focused coverage exercised:

- `MemoryUpdateListenerTest`
- `JsonTurnLogAppenderTest`
- `TurnProcessorTest`
- `ToolProgressUXTest`
- `ModeControllerTest`
- `SimpleCommandsTest`

## Verification

- RED architecture ratchet:
  `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  failed as expected with four removed `Result` baseline rows.
- Focused GREEN test run:
  `.\gradlew.bat test --tests "dev.talos.runtime.MemoryUpdateListenerTest" --tests "dev.talos.runtime.JsonTurnLogAppenderTest" --tests "dev.talos.runtime.TurnProcessorTest" --tests "dev.talos.runtime.ToolProgressUXTest" --tests "dev.talos.cli.modes.ModeControllerTest" --tests "dev.talos.cli.repl.slash.SimpleCommandsTest" --no-daemon`:
  passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  passed.
- Final full verification before commit:
  `git diff --check` and `.\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

Do not move `Context` or `SessionMemory` mechanically.

After T363, inspect the remaining `30` baseline entries. The runtime/CLI
boundary still has several larger seams:

- runtime still consumes CLI `Context`;
- runtime still consumes CLI `ModeController`;
- core and runtime still depend on `SessionMemory`;
- the command execution tool still depends on runtime command contracts;
- SPI purity and RAG context-ledger ownership remain separate design tracks.

The next ticket should start from source evidence. If no adapter-local
runtime/CLI edge remains, pause for a short ownership decision around
`Context`, `ModeController`, and `SessionMemory` before doing another package
move.

Confidence: high.
