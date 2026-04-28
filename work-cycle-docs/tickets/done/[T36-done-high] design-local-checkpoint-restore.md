# [T36-done-high] Ticket: Design Local Checkpoint/Restore
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/05-local-checkpoint-restore.md`

## Context

Talos asks before mutating files, but it does not yet create a first-class
restore point before approved mutation. Checkpoint/restore is a trust layer that
should exist before dangerous tool expansion.

## Goal

Design local checkpoint/restore before mutation.

## Non-Goals

- Do not implement checkpointing.
- Do not add shell or browser tools.
- Do not rely on cloud storage.
- Do not require global Git state in the user's workspace.

## Implementation Notes

The design must address:

- Windows-first storage
- JGit/shadow repository option
- dependency and storage tradeoffs
- metadata schema
- checkpoint timing
- failure policy
- restore behavior
- trace correlation
- interaction with approval and permissions

## Acceptance Criteria

- Design defines where checkpoint data lives.
- Design evaluates JGit/shadow repo approach.
- Design defines checkpoint metadata schema.
- Design defines checkpoint creation timing.
- Design defines failure policy, including fail-closed behavior when enabled.
- Design defines restore command/path.
- Design defines trace correlation.
- No runtime implementation is included.

## Tests / Evidence

Run:

```powershell
./gradlew.bat test --no-daemon
```

## Work-Test Cycle Notes

Design-only ticket. This unblocks T37.

## Known Risks

- Copying too much workspace data can be slow or surprising.
- Copying too little can make restore untrustworthy.
- Git-based snapshots need careful handling in non-Git workspaces.

## Current Code Read

- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`
- `src/main/java/dev/talos/cli/repl/slash/UndoCommand.java`
- `src/main/java/dev/talos/runtime/policy/DeclarativePermissionPolicy.java`
- `build.gradle.kts`

## Implementation Summary

- Added `docs/architecture/05-local-checkpoint-restore.md`.
- Defined local checkpoint/restore purpose, non-goals, storage location,
  backend options, runtime types, checkpoint timing, metadata schema, failure
  policy, restore behavior, permission interaction, trace correlation,
  retention, tests, and T37 implementation handoff.
- Evaluated JDK file-bundle storage versus a future JGit shadow repository.
  The design recommends a small `CheckpointStore` abstraction and a JDK
  file-bundle first implementation unless T37 explicitly verifies adding JGit.
- Preserved the constraint that this ticket does not implement runtime
  checkpointing.

## Tests Run

```powershell
./gradlew.bat test --no-daemon
```

Result: PASS

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Manual Talos Check Result

Not required. T36 is a design-only ticket and does not change runtime behavior.

## Known Follow-Ups

- T37 should implement checkpoint/restore v1 using this design.
- T37 must decide whether checkpointing is enabled by default immediately or
  staged through config for one release.
