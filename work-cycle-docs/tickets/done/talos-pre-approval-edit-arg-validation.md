# [done] Ticket: Pre-Approval Edit Argument Validation

Date: 2026-04-25
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-streaming-protocol-fence-and-pretool-prose-display.md`
- `work-cycle-docs/work-test-cycle.md`

## Why This Ticket Exists

Installed CLI verification for the streaming protocol display ticket showed a
malformed `talos.edit_file` call reaching the approval prompt with empty
`old_string` and `new_string` values.

The approval gate still prevented mutation, and `FileEditTool` would reject an
empty `old_string` during execution. The issue is earlier than tool execution:
Talos should not ask the user to approve a malformed write operation that cannot
validly run.

## Problem

`TurnProcessor` currently routes mutating tool calls through approval before
tool-specific execution validation. For `talos.edit_file`, that means a call
with an empty `old_string` can produce a user-facing approval prompt even though
the tool will later reject it as invalid.

This is confusing and weakens approval discipline:
- users are asked to approve an impossible edit
- the approval preview can show blank replace/with fields
- repeated malformed edit attempts can waste a turn before failure policy stops
  the loop

## Goal

Reject clearly malformed mutating tool arguments before the approval prompt.

The first slice should focus on `talos.edit_file`:
- `path` must be present and non-blank
- `old_string` must be present and non-empty
- `new_string` must be present
- no-op edits where `old_string == new_string` should not ask approval

The final answer should report that no file was changed because the proposed
tool call was invalid, not because the user denied a valid write.

## Scope

### In scope

- Add a pre-approval validation seam for mutating tool calls.
- Implement `talos.edit_file` validation before approval.
- Add tests proving invalid edit args do not trigger approval.
- Preserve existing `FileEditTool` execution validation as defense in depth.

### Out of scope

- Broad schema validation for every tool.
- Changing approval policy for valid mutating calls.
- Changing parser behavior.
- Changing `write_file` semantics unless the same validation seam makes a
  minimal required-argument check obvious.

## Proposed Work

Likely implementation directions:

- Add a small validation helper near `TurnProcessor.executeTool(...)`, or expose
  a `ToolPreflightValidator` under `dev.talos.runtime`.
- Keep the validation structured: return a `ToolResult.fail(...)` before
  approval when the call is invalid.
- Avoid parsing human approval previews to infer validity.
- Keep `FileEditTool` validation intact so direct tool execution remains safe.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`
- `src/test/java/dev/talos/runtime/ApprovalGatedToolTest.java`
- possibly `src/test/java/dev/talos/runtime/TurnProcessorTest.java`

## Test / Verification Plan

- Unit: invalid `talos.edit_file` with empty `old_string` returns failure without
  invoking the approval gate.
- Unit: invalid no-op `talos.edit_file` returns failure without invoking the
  approval gate.
- Unit: valid `talos.edit_file` still invokes approval.
- E2E or executor-path scenario if a compact scripted case already exists.
- Installed CLI verification after implementation because this affects approval
  UX.

## Acceptance Criteria

- malformed `edit_file` calls do not ask for approval
- valid `edit_file` calls still ask for approval
- no workspace files change for rejected invalid calls
- final/user-visible output distinguishes invalid tool arguments from denied
  approval

## Completion Notes

Implemented a pre-approval `talos.edit_file` validation seam in
`TurnProcessor`. Invalid edit calls now fail before approval when the target
path is missing, `old_string` is empty, `new_string` is missing, or the edit is
a no-op. Empty `new_string` remains valid for deletions.

Extended `ToolCallLoop.ToolOutcome` with a structured error code and added a
central invalid-mutation outcome summary so final answers distinguish invalid
tool arguments from approval denial.

Verification completed:
- `./gradlew.bat test --tests "dev.talos.runtime.ApprovalGatedToolTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest"`
- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.runtime.outcome.MutationOutcomeTest"`
- `./gradlew.bat test`
- `./gradlew.bat e2eTest`
- `./gradlew.bat check`
- Installed Talos verification in `local/playground/horror-synth-site`

Manual installed run notes:
- read-only selector inspection stayed read-only
- approval denial stopped after one failed mutating call
- no raw tool-call protocol JSON leaked
- playground files remained unchanged
- observed unrelated Ollama embedding NaN fallback during retrieval; Talos
  recovered through BM25-only retrieval, so this did not block the ticket
