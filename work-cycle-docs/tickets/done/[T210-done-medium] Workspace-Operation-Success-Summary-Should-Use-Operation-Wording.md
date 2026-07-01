# T210 - Workspace Operation Success Summary Should Use Operation Wording

Severity: medium
Status: done

## Problem

Workspace operation turns now correctly use dedicated operation tools, but the runtime success banner still says:

`[File write/readback passed. No task-specific verifier was applicable, so task completion was not verified...]`

That wording is inaccurate for `talos.move_path`, `talos.copy_path`, `talos.rename_path`, and `talos.mkdir`. These are workspace operations, not file write/readback operations.

The behavior is correct, but the user-visible status language is misleading and makes audit interpretation harder.

## Evidence

Audit:
`local/manual-testing/llama-cpp-t209-focused-re-audit-20260507-231118/`

Examples:
- Qwen move/copy/rename/mkdir turns: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` around lines 459, 1255, 1688, 1884.
- GPT-OSS move/copy/rename/mkdir turns: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around lines 457, 1249, 1671, 2101.

In each case, the tool call is correct, but the status banner says `File write/readback passed`.

## Scope

- Update runtime-generated success/readback summary wording for workspace operation tools.
- Use wording like `Workspace operation/readback passed` or a clearer equivalent.
- Preserve the existing truthfulness boundary: if no task-specific verifier applies, do not claim full task-specific verification.
- Preserve failure-dominant output behavior.

## Acceptance

- Tests cover successful `talos.move_path`, `talos.copy_path`, `talos.rename_path`, and `talos.mkdir` outcomes and assert the status banner does not say `File write/readback passed`.
- Tests assert successful ordinary file write/edit outcomes still use appropriate file write/readback wording.
- Tests assert partial/failure workspace operation outcomes remain failure-dominant.
- Focused audit no longer shows file-write wording for workspace operation turns.

## Implementation

- Updated readback-only success annotation selection in `ExecutionOutcome` so successful non-write workspace operation outcomes use `Workspace operation/readback passed`.
- Kept ordinary file write/readback outcomes on the existing `File write/readback passed` wording.
- Preserved failure-dominant output for partial and failed workspace operation outcomes.
- Identifies workspace operations from `WorkspaceOperationPlan` when available, with canonical workspace operation tool names as a fallback.

## Verification

- `.\gradlew.bat test --tests dev.talos.cli.modes.ExecutionOutcomeTest.workspaceOperationReadbackSummaryUsesOperationWording --no-daemon`
  - RED first: failed because the runtime still emitted `File write/readback passed`.
- `.\gradlew.bat test --tests dev.talos.cli.modes.ExecutionOutcomeTest.workspaceOperationReadbackSummaryUsesOperationWording --tests dev.talos.cli.modes.ExecutionOutcomeTest.partialWorkspaceOperationDoesNotUseReadbackSuccessBanner --tests dev.talos.cli.modes.ExecutionOutcomeTest.failedWorkspaceOperationDoesNotUseReadbackSuccessBanner --no-daemon`
  - PASS.
- `.\gradlew.bat test --tests dev.talos.cli.modes.ExecutionOutcomeTest --no-daemon`
  - PASS.
- `.\gradlew.bat test --no-daemon`
  - PASS.
- `.\gradlew.bat build installDist --no-daemon`
  - PASS.

## Focused Audit

Focused re-audit:
`local/manual-testing/llama-cpp-t210-focused-re-audit-20260507-233536/`

Findings:
`local/manual-testing/llama-cpp-t210-focused-re-audit-20260507-233536/FINDINGS-LLAMA-CPP-T210-FOCUSED-RE-AUDIT.md`

Result:
- `File write/readback passed`: 0 occurrences across both model transcripts.
- `Workspace operation/readback passed`: appears on move/copy/rename/mkdir operation turns for both Qwen and GPT-OSS.
- No protected marker leakage found.
- T211 remains open for directory-aware verify-only checks.

## Non-Goals

- Do not change workspace operation tool semantics.
- Do not add new filesystem tools.
- Do not broaden static web verification.
