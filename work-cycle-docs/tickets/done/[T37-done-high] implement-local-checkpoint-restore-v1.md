# [T37-done-high] Ticket: Implement Local Checkpoint/Restore V1
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T36 checkpoint/restore design ticket
- `docs/architecture/05-local-checkpoint-restore.md`

## Context

Checkpoint/restore should become Talos's local trust layer before tool surfaces
expand. The first implementation must be local, bounded, and Windows-first.

## Goal

Create a checkpoint before approved mutation and provide a restore path.

## Non-Goals

- Do not add shell/browser tools.
- Do not make Talos a background daemon.
- Do not sync checkpoints to cloud.
- Do not change Git history in the user's repository.

## Implementation Notes

- Create checkpoint after approval and before the first mutating tool in a
  mutating turn.
- Attach checkpoint id to trace.
- Restore should revert files covered by the checkpoint.
- If checkpointing is enabled and creation fails, mutation fails closed.
- Keep checkpoint storage local and inspectable.

## Acceptance Criteria

- Checkpoint is created after approval and before first mutating tool in a
  mutating turn.
- Checkpoint id is captured in trace.
- Restore reverts files for the checkpoint.
- If checkpoint is enabled and creation fails, mutation does not proceed.
- Tests prove successful restore.
- Tests prove fail-closed behavior.
- No shell/browser expansion is introduced.

## Tests / Evidence

Run focused checkpoint tests, then:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Manual installed Talos verification is required.

## Work-Test Cycle Notes

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

This is file-safety-sensitive, so full `check` and manual verification were
run before marking done.

## Known Risks

- Checkpoint failure must not become a silent best-effort warning when the
  feature is enabled.
- Restore must not affect files outside the checkpoint scope.

## Current Code Read

- `docs/architecture/05-local-checkpoint-restore.md`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
- `src/main/java/dev/talos/runtime/SessionStore.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/cli/repl/slash/UndoCommand.java`
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`

## Planned Tests

- `FileBundleCheckpointStoreTest`
- `TurnProcessorCheckpointTest`
- `CheckpointCommandTest`
- focused e2e and full `check`
- installed manual Talos verification

## Implementation Summary

- Added `dev.talos.runtime.checkpoint` with:
  - `CheckpointConfig`
  - `CheckpointService`
  - `CheckpointStore`
  - `FileBundleCheckpointStore`
  - `CheckpointCaptureResult`
  - `CheckpointRestoreResult`
- Wired `TurnProcessor` to create a checkpoint after approval/permission
  success and before mutating tool execution.
- Added fail-closed behavior: required checkpoint failure blocks mutation before
  the write/edit tool runs.
- Added checkpoint summary/events to `LocalTurnTraceCapture`.
- Added `/checkpoint list` and `/checkpoint restore <id>`.
- Registered `CheckpointCommand` in `TalosBootstrap`.
- Updated `/last trace` display to show checkpoint status and id.

## Tests Run

```powershell
./gradlew.bat test --tests "dev.talos.runtime.checkpoint.FileBundleCheckpointStoreTest" --tests "dev.talos.runtime.TurnProcessorCheckpointTest" --tests "dev.talos.cli.repl.slash.CheckpointCommandTest" --no-daemon
```

Initial result: RED, missing checkpoint classes and command.

```powershell
./gradlew.bat test --tests "dev.talos.runtime.checkpoint.FileBundleCheckpointStoreTest" --tests "dev.talos.runtime.TurnProcessorCheckpointTest" --tests "dev.talos.cli.repl.slash.CheckpointCommandTest" --no-daemon
```

Result after implementation: PASS

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest.traceViewIncludesLocalTraceWhenTurnHasTraceId" --no-daemon
```

Initial result: RED, `/last trace` did not display checkpoint summary.

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest.traceViewIncludesLocalTraceWhenTurnHasTraceId" --no-daemon
```

Result after display update: PASS

```powershell
./gradlew.bat test --tests "dev.talos.runtime.checkpoint.FileBundleCheckpointStoreTest" --tests "dev.talos.runtime.TurnProcessorCheckpointTest" --tests "dev.talos.cli.repl.slash.CheckpointCommandTest" --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Result: PASS

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Result: PASS

## Manual Talos Check Result

Command:

```powershell
cd local/manual-workspaces/T37
talos
/session clear
/debug trace
Overwrite index.html with a full replacement. Content: AFTER. Use write_file for index.html.
y
/last trace
/checkpoint list
/q
```

Workspace:

`local/manual-workspaces/T37`

Model:

`qwen2.5-coder:14b`

Prompt:

`Overwrite index.html with a full replacement. Content: AFTER. Use write_file for index.html.`

Approval choice:

`y`

Observed tools:

`talos.write_file`

Files changed:

`index.html` changed from `BEFORE` to `AFTER.`

Output file:

`local/manual-testing/T37-output.txt`

Pass/fail:

PASS

Notes:

- `/last trace` showed `Checkpoint: CREATED chk-6ed1ea68-3b0c-4da8-9a7f-42c31fab2b08`.
- `/checkpoint list` showed the created checkpoint id.

Restore command:

```powershell
/checkpoint restore chk-6ed1ea68-3b0c-4da8-9a7f-42c31fab2b08
y
```

Restore output file:

`local/manual-testing/T37-restore-output.txt`

Restore result:

PASS. `index.html` was restored to `BEFORE`.

## Known Follow-Ups

- T40 was created for a separate manual finding: clear mutation requests with
  formatting negations such as "do not use placeholders" can be misclassified
  as read-only.
- Future work should add retention/cleanup for old checkpoint artifacts.
