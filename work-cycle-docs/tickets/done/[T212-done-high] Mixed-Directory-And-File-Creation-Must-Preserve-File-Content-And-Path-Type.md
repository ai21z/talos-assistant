# T212 - Mixed Directory-And-File Creation Must Preserve File Content And Path Type

Status: done
Severity: high

## Problem

The T61-R full llama.cpp two-model audit found that a mixed request could be accepted with the wrong path type:

`Create a directory named workspace-notes and create workspace-notes/summary.txt containing exactly created by audit.`

Qwen created `workspace-notes/summary.txt` as a file with the exact content, but GPT-OSS created `workspace-notes/summary.txt` as a directory. Talos still reported workspace-operation/readback success.

## Root Cause

- `TaskExpectationResolver` did not resolve `create <target> containing exactly <literal>` into a literal exact-file expectation.
- `ToolSurfacePlanner` narrowed the turn to a mkdir-only workspace-operation surface because `WorkspaceOperationIntent` detected the directory phrase.
- `ActionObligationPolicy` still classified the mixed request as `WORKSPACE_OPERATION_REQUIRED`, so the model-facing frame told the model not to use generic `talos.write_file` even though the request also required an exact file write.
- Without the literal expectation, `StaticTaskVerifier` fell back to weak workspace-operation readback.

## Fix

- Added literal expectation support for target-specific `create/write/add <target> containing exactly <literal>` wording.
- Kept exact-file expectation turns on the normal file-mutation surface instead of narrowing them to a single workspace operation tool.
- Kept exact-file expectation turns under `MUTATING_TOOL_REQUIRED` instead of `WORKSPACE_OPERATION_REQUIRED`.
- Widened exact-verification failure summary detection so non-readable exact targets are reported as exact content verification failures.
- Added tests for expectation parsing, tool-surface planning, action-obligation derivation, prompt-frame construction, static verification, and final failure dominance.

## Verification

Targeted tests:

`.\gradlew.bat test --tests dev.talos.runtime.expectation.TaskExpectationResolverTest --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest --tests dev.talos.runtime.policy.ActionObligationPolicyTest --tests dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest --tests dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest --tests dev.talos.cli.modes.ExecutionOutcomeTest --no-daemon`

Result: passed.

Full verification:

`.\gradlew.bat build installDist --no-daemon`

Result: passed.

Focused audit:

`local/manual-testing/llama-cpp-t212-focused-re-audit-20260508-015503/FINDINGS-LLAMA-CPP-T212-FOCUSED-RE-AUDIT.md`

Result: clean for T212. Both Qwen and GPT-OSS received `MUTATING_TOOL_REQUIRED`, `[ExactFileWrite]`, and visible/native/prompt tools including `talos.mkdir` and `talos.write_file`. Both produced `workspace-notes/summary.txt` as a file containing exactly `created by audit`.
