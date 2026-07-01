# T147 - Explicit Batch Workspace Apply Intent Classification

Severity: medium
Status: done

## Problem

The product workflow audit showed that an explicit `talos.apply_workspace_batch`
request can be classified as read-only.

Prompt:

`Use talos.apply_workspace_batch only. Apply operations_json for exactly these operations ...`

The task contract became `WORKSPACE_EXPLAIN` with mutation disabled. Qwen's
batch tool call was blocked by the read-only contract, and GPT-OSS stayed in
read-only inspection.

## Scope

- Classify explicit `talos.apply_workspace_batch`, `operations_json`, and
  "apply these operations" wording as mutation intent.
- Expose mutation tools for that turn under normal approval/checkpoint policy.
- Preserve read-only classification for advisory questions about batch apply.

## Acceptance

- Explicit batch-apply prompts classify as mutation-allowed.
- `talos.apply_workspace_batch` is visible in the apply tool surface.
- Advisory prompts such as "explain what apply_workspace_batch does" remain
  read-only.
- Tests cover explicit tool name, `operations_json`, and advisory wording.

## Evidence

- `local/manual-testing/llama-cpp-product-workflow-audit-20260505-120139/`
- Qwen trace: `trc-13624c9f-6f3b-41b6-ab97-37a887220df9`
- GPT-OSS trace: `trc-0aad7d57-9ff9-4d47-bb9b-9aedb7f77d56`

## Non-Goals

- No new batch operation kinds.
- No delete support.
- No command profile expansion.

## Result

- Added an explicit batch workspace apply mutation-intent classifier for
  `talos.apply_workspace_batch`, `apply operations_json`, and "apply these
  operations" prompts.
- Preserved advisory/read-only classification for explanations about the batch
  tool and `operations_json`.
- Added resolver/tool-surface tests proving the apply surface exposes
  `talos.apply_workspace_batch` only after the contract is mutation-enabled.
