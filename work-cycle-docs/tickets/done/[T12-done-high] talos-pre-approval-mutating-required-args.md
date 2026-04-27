# [done] Ticket: Pre-Approval Required-Argument Validation For Mutating Tools
Date: 2026-04-27
Priority: high
Status: done
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

## Current Code Read

- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/tools/ToolValidation.java`
- `src/main/java/dev/talos/tools/ToolRegistry.java`
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`
- `src/test/java/dev/talos/runtime/TurnProcessorTest.java`
- `src/e2eTest/resources/scenarios/21-mutation-prompt-empty-edit-args-stops-cleanly.json`
- `src/e2eTest/resources/scenarios/34-empty-edit-args-cross-path-stop.json`

## Planned Tests

- `./gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest"`
- Focused JSON scenario for `write_file` missing `content`
- `./gradlew.bat e2eTest`
- `./gradlew.bat check`
- Manual installed Talos check in `local/manual-workspaces/T12/`

## Implementation Summary

- Added `talos.write_file` pre-approval required-argument validation for `path` and `content`.
- Kept `content` presence-only so empty file writes remain valid, matching `FileWriteTool` behavior.
- Made write/edit pre-approval tool-name checks alias-aware.
- Preserved normal approval behavior for valid mutating calls.
- Added deterministic unit coverage for missing write `content`, missing write `path`, missing edit `path`, empty edit `old_string`, and missing edit `new_string`.
- Added JSON-backed e2e coverage for `write_file` missing `content` proving no approval prompt is requested and no file is changed.

## Tests Run

- `./gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest.writeFileMissingContentFailsBeforeApproval" --tests "dev.talos.runtime.TurnProcessorTest.writeFileMissingPathFailsBeforeApproval" --tests "dev.talos.runtime.TurnProcessorTest.editFileMissingRequiredArgsFailBeforeApproval" --tests "dev.talos.runtime.TurnProcessorTest.validWriteFileStillRequestsApproval"` — failed before implementation for the two `write_file` cases, then passed after implementation
- `./gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest"` — passed
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.writeFileMissingContentBlocksBeforeApproval"` — passed
- `./gradlew.bat e2eTest` — passed
- `./gradlew.bat check` — passed

## Work-Test-Cycle Loop Used

- Inner dev loop.
- Candidate loop was not run because this was one ticket inside the open-ticket batch, not a declared versioned candidate.

## Commit

- Implementation commit: `6947595 T12: validate mutating required args before approval`

## Manual Talos Check Result

Command:
- `pwsh .\tools\uninstall-windows.ps1 -Quiet`
- `./gradlew.bat clean installDist --no-daemon`
- `pwsh .\tools\install-windows.ps1 -Force -Quiet`
- Piped `/session clear`, `/debug trace`, manual prompts, approval responses, and `/q` into installed `talos.bat`

Workspace:
- `local/manual-workspaces/T12/`

Model:
- `qwen2.5-coder:14b`

Prompts:
- `Use the file edit tool to change only the page title in index.html from T12 Manual to Should Not Apply.`
- `Change index.html: replace the title T12 Manual with Should Not Apply.`
- `Change index.html: replace the title T12 Manual with Talos Manual Check.`

Approval choice:
- First explicit `Change index.html...Should Not Apply` approval was denied with `n`.
- Second explicit `Change index.html...Talos Manual Check` approval was accepted with `y`.

Observed tools:
- Denied valid mutation: `talos.edit_file`; approval prompt appeared and denial preserved the file.
- Approved valid mutation: `talos.read_file`, `talos.edit_file`; approval prompt appeared and the title changed.

Files changed:
- Denied run: none.
- Approved run: `index.html` title changed to `Talos Manual Check`.

Output file:
- `local/manual-testing/T12-output.txt`

Pass/fail:
- Pass for T12 compatibility: valid mutating calls still require approval, denial preserves files, and approval applies the edit.
- The invalid missing-argument behavior is covered by deterministic unit/e2e tests rather than live-model prompting.

Notes:
- Manual testing also surfaced a separate intent-classification gap: `Use the file edit tool to change...` was treated as `READ_ONLY_QA` and blocked before approval. That is outside T12's required-argument validation scope and should be handled as a follow-up intent ticket if not covered by the upcoming repair/intent work.

## Known Follow-Ups

- Add or fold in intent handling for prompts like `Use the file edit tool to change...` if the upcoming repair/intent tickets do not already cover it.

## Acceptance Criteria

- Missing required mutating parameters never trigger an approval prompt.
- The model receives a structured invalid-params failure.
- The trace records the invalid-params block.
- Existing valid write/edit approval behavior remains unchanged.
