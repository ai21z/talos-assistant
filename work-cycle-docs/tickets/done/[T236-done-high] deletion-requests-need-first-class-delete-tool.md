# T236 - Deletion Requests Need First-Class Delete Tool

Status: done
Priority: high

## Evidence Summary

Source audit:

- `local/manual-testing/user-perspective-broad-audit-20260511-080320/FINDINGS-USER-PERSPECTIVE-BROAD-AUDIT.md`
- Qwen transcript: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1979-1984`
- GPT-OSS transcript: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1987-2019`

Observed behavior:

```text
User asked: Delete docs/synthwave-webpage-plan.md.
Qwen attempted unsupported apply_workspace_batch op delete_path.
GPT-OSS wrote empty content to docs/synthwave-webpage-plan.md.
The target file still existed after the turn.
```

Expected behavior:

```text
Talos should support safe deletion as a first-class approved workspace
operation, or deterministically say deletion is unsupported without attempting
empty overwrites or invented batch operations.
```

## Classification

Primary taxonomy bucket:

- `TOOL_SURFACE`

Secondary buckets:

- `PERMISSION`
- `CHECKPOINT`
- `OUTCOME_TRUTH`
- `WORKSPACE_OPERATION`

## Goal

Add safe delete support for user-requested file/folder deletion inside the
workspace.

## Acceptance Criteria

- A natural prompt such as `Delete docs/foo.md` uses a first-class delete tool
  or batch delete operation.
- Approval is required before deletion.
- Protected files still require protected-path policy checks.
- Path sandbox validation prevents deleting outside the workspace.
- Deletion is checkpointed or otherwise recoverable according to the existing
  mutation safety model.
- Final output distinguishes:
  - deleted,
  - not found,
  - approval denied,
  - protected/sandbox blocked,
  - unsupported directory deletion if recursive behavior is intentionally not supported.
- The model is not encouraged to emulate deletion by writing empty content.
- `apply_workspace_batch` either supports `delete_path` explicitly or rejects it
  with a deterministic product-level answer before model drift causes damage.

## Completion Notes

- Added `talos.delete_path` as a destructive workspace operation.
- Explicit delete requests now receive a delete-only tool surface.
- `apply_workspace_batch` now supports `delete_path` and marks delete batches
  destructive for approval/checkpoint planning.
- Directory deletion requires explicit `recursive=true`; workspace-root and
  sandbox escapes are blocked.
- Added direct tool, batch, planner, alias, verifier, and executor tests.
- Verification: `.\gradlew test`.

## Non-Goals

- No shell `rm` / `del` escape hatch.
- No deleting outside the workspace.
- No silent recursive directory deletion without explicit policy.

## Suggested Tests

- Unit: `delete_file`/`delete_path` succeeds for an ordinary file after approval.
- Unit: deletion outside workspace is blocked pre-approval.
- Unit: protected deletion requires approval and respects denial.
- E2E: create file, delete it, list directory, verify absent.
- Regression: empty `write_file` is not treated as deletion.
