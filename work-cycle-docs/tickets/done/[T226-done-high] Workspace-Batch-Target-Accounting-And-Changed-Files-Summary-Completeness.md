# T226 - Workspace Batch Target Accounting And Changed-Files Summary Completeness

Severity: high

Status: done

## Problem

The post-T225 broad llama.cpp audit found that `talos.apply_workspace_batch` can successfully mutate multiple paths while Talos records only one changed path and frames expected targets incorrectly.

Audit prompt:

> Use talos.apply_workspace_batch to create directories batch-one and batch-two and copy styles.css to batch-one/styles-copy.css.

Observed:

- Prompt/debug trace expected targets were `styles.css` and `batch-one/styles-copy.css`.
- `styles.css` is a source path, not a mutation target.
- `batch-one` and `batch-two` were requested created directories but were missing from expected targets.
- Later changed-files answers listed only `batch-one` for the batch turn.
- The successful copy destination `batch-one/styles-copy.css` and created directory `batch-two` were omitted.

Evidence:

- `local/manual-testing/llama-cpp-post-t225-broad-product-audit-20260508-082833/FINDINGS-LLAMA-CPP-POST-T225-BROAD-PRODUCT-AUDIT.md`
- `local/manual-testing/llama-cpp-post-t225-broad-product-audit-20260508-082833/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
- `local/manual-testing/llama-cpp-post-t225-broad-product-audit-20260508-082833/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`
- Qwen trace for batch turn 23: `SESSION-ARTIFACTS-LLAMA-CPP-QWEN-14B/traces/.../000023-trc-ae58acce-8abf-435a-acf4-c9ccbb84e777.json`

## Scope

- For natural workspace batch requests, expected targets should represent mutation outputs:
  - created directories
  - copy/move/rename destinations
  - not copy sources
- Successful batch tool-call audit should preserve every changed path, not only the first path.
- Runtime-owned changed-files summaries should list every successful batch effect.
- Preserve existing single-path behavior for write/edit/mkdir/copy/move/rename tools.
- Preserve backward compatibility for old turn records with only one `pathHint`.

## Acceptance

- [x] Tests cover the exact audit prompt and assert expected targets are:
  - `batch-one`
  - `batch-two`
  - `batch-one/styles-copy.css`
- [x] Tests assert `styles.css` is not treated as a required mutation target for the copy source.
- [x] Tests assert a successful `talos.apply_workspace_batch` audit records all changed paths.
- [x] Tests assert `ChangeSummaryContext.renderForChangeSummaryQuestion()` includes all successful batch effects.
- [x] Full Gradle tests and build/install pass.
- [x] A focused two-model audit confirms changed-files answers include `batch-one`, `batch-two`, and `batch-one/styles-copy.css`.

## Implementation Notes

- Added multi-path audit hints to `TurnRecord.ToolCallSummary` while preserving the existing primary `pathHint` field for compatibility.
- `TurnProcessor` now records every changed path from `WorkspaceOperationPlan.changedPaths()` for successful workspace operations.
- `ChangeSummaryContext` consumes all path hints from a successful mutating tool call.
- `TaskContractResolver` now treats explicit `apply_workspace_batch` natural-language prompts as batch requests and extracts created directories plus copy/move/rename destinations as expected targets, excluding copy sources.

## Verification

- Red/green targeted tests:
  - `TaskContractResolverTest.batchWorkspaceNaturalPromptTargetsCreatedDirsAndCopyDestinationNotSource`
  - `WorkspaceBatchTurnProcessorTest.successfulBatchAuditRecordsAllChangedPaths`
  - `ActiveTaskContextUpdateListenerTest.batchWorkspaceMutationRecordsEveryChangedPathInSummary`
- Targeted suites:
  - `.\gradlew.bat test --tests dev.talos.runtime.task.TaskContractResolverTest --tests dev.talos.runtime.ActiveTaskContextUpdateListenerTest --tests dev.talos.runtime.WorkspaceBatchTurnProcessorTest --no-daemon`
  - `.\gradlew.bat test --tests dev.talos.runtime.JsonSessionStoreTurnsTest --tests dev.talos.runtime.JsonTurnLogAppenderTest --tests dev.talos.cli.repl.slash.ExplainLastTurnCommandTest --no-daemon`
- Full verification:
  - `.\gradlew.bat test --no-daemon`
  - `.\gradlew.bat build installDist --no-daemon`
  - `git diff --check`
- Focused product audit:
  - `local/manual-testing/t226-batch-accounting-focused-audit-20260508-090325/FINDINGS-LLAMA-CPP-T226-BATCH-ACCOUNTING.md`
