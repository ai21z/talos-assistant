# [T37-open-high] Ticket: Implement Local Checkpoint/Restore V1
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T36 checkpoint/restore design ticket

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

Use the inner dev loop while implementing. This is file-safety-sensitive, so
full `check` and manual verification are required before marking done.

## Known Risks

- Checkpoint failure must not become a silent best-effort warning when the
  feature is enabled.
- Restore must not affect files outside the checkpoint scope.
