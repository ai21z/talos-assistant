# [open] Ticket: Pre-Approval Required-Argument Validation For Mutating Tools
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-pre-approval-edit-arg-validation.md`
- `work-cycle-docs/tickets/done/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed Talos requesting approval for an invalid mutating tool
call:

```text
Using write_file: styles.css
Approval required
...
error write_file: Missing required parameter: content
```

The approval prompt should never appear for a structurally invalid write.

## Problem

`edit_file` has some pre-approval validation, but `write_file` with missing
`content` still reached the approval gate. This trains the user to approve
nonsense and weakens trust in the approval UI.

Required-argument validation must happen before user approval for every
mutating tool.

## Goal

Invalid mutating calls must be rejected before approval and fed back to the
tool loop as structured `INVALID_PARAMS` failures.

## Scope

### In scope

- Validate required parameters for all current mutating tools before approval:
  - `talos.write_file`: `path`, `content`
  - `talos.edit_file`: `path`, `old_string`, `new_string`
- Ensure invalid mutating calls record a blocked/failed outcome.
- Ensure no approval prompt is shown for structurally invalid mutating calls.
- Add deterministic tests for missing `content`, missing `path`, empty
  `old_string`, and missing `new_string`.

### Out of scope

- Semantic content validation.
- New mutation tools.
- Changing approval wording for valid mutations.

## Proposed Work

1. Centralize required-argument validation in `TurnProcessor` or a small
   pre-approval validator so every mutating tool passes through the same gate.
2. Reuse existing tool schemas where practical instead of duplicating ad hoc
   checks.
3. Return `ToolResult.fail(ToolError.invalidParams(...))` before approval.
4. Make the debug trace show the blocked invalid params reason.
5. Add unit and E2E coverage proving approval is not requested.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/tools/ToolValidation.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/test/java/dev/talos/runtime/TurnProcessorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused unit tests around pre-approval validation.
- E2E scenario where a scripted model emits `write_file` without `content`.
- Confirm the final answer says no file was changed and no approval was needed.

## Acceptance Criteria

- Missing required mutating parameters never trigger an approval prompt.
- The model receives a structured invalid-params failure.
- The trace records the invalid-params block.
- Existing valid write/edit approval behavior remains unchanged.
