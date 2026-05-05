# T146 - Workspace Operation Verification For Organize And Batch Tools

Severity: high
Status: open

## Problem

The product workflow audit showed that copy/move/rename and batch workspace
operations execute through tools, but the verification layer still treats them
like simple file mutations.

For organize workflows, Talos expected moved source/intermediate paths to remain
readable and did not verify operation-specific facts such as destination exists
or source was moved away. For batch workflows, `talos.apply_workspace_batch`
succeeded but did not expose target paths to verification.

## Scope

- Add operation-aware verification for workspace organize operations:
  - copy: source remains and destination exists;
  - move: source no longer exists and destination exists;
  - rename: old sibling no longer exists and renamed path exists;
  - batch: expose per-operation source/destination targets.
- Prevent successful move/rename from causing retry loops that repeat the same
  operation against now-missing source paths.
- Keep failure-dominant output when an operation actually fails.

## Acceptance

- Qwen-shaped sequence `copy -> move -> rename` verifies as complete when the
  final workspace state is correct.
- A repeated move after the source already moved is not triggered by a false
  verifier failure.
- `talos.apply_workspace_batch` exposes enough operation result metadata for
  verification.
- Partial batch failure reports applied and failed operation paths.
- Tests assert operation-specific verification facts, not only final prose.

## Evidence

- `local/manual-testing/llama-cpp-product-workflow-audit-20260505-120139/`
- Qwen trace: `trc-41122dba-8118-4036-a98b-082ec413bf28`
- GPT-OSS trace: `trc-c6b78d8c-1a90-4902-9014-00a6930e8798`

## Non-Goals

- No delete operation.
- No generic shell or command profile expansion.
- No large verifier rewrite outside workspace operation semantics.
