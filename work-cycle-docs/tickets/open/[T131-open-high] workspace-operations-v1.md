# T131 - Workspace Operations V1

Severity: high
Status: open

## Problem

Talos can indirectly create directories when `write_file` creates parent folders, but workspace organization is not a first-class capability. A real local workspace assistant should safely create and organize folders/files with runtime-owned summaries and approval.

## Scope

- Add first-class workspace operation tools:
  - `talos.mkdir`
  - `talos.move_path`
  - `talos.copy_path`
  - `talos.rename_path`
- Consider `talos.delete_path` only if T130 bundle checkpoint and destructive approval are ready.
- Use capability metadata from T128 and tool-surface planning from T129.
- Use workspace operation planning/checkpointing from T130.

## Acceptance

- All source and destination paths are sandboxed inside the workspace.
- Approval is required for write/organize operations.
- Overwrite behavior is explicit and tested.
- Runtime-owned summary lists created, moved, copied, and renamed paths.
- Failure-dominant output replaces model-authored success prose on invalid operations.
- Tests cover path traversal, protected paths, overwrite handling, missing source, existing destination, and successful operations.

## Non-Goals

- No shell command execution.
- No batch apply UX beyond what T130 supports internally.
- No binary document tools.

## Verification

- Focused unit tests for each tool.
- Tool-loop integration tests for approval and failure-dominant outcomes.
- `.\gradlew.bat --no-daemon build installDist`.
