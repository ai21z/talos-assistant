# [T36-open-high] Ticket: Design Local Checkpoint/Restore
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`

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

Design-only ticket. This should unblock T37.

## Known Risks

- Copying too much workspace data can be slow or surprising.
- Copying too little can make restore untrustworthy.
- Git-based snapshots need careful handling in non-Git workspaces.
