# T92 - Runtime-Owned Changed-Files Summary

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: T91 dual-model audit follow-up

## Problem

T91 made changed-files questions tool-free and protected-read safe, but the answer still depended on previous assistant prose. In the T91 dual-model audit, both models asked `What files changed during this audit? Do not read protected files.` after failed static verification. Talos correctly avoided protected reads and made no tool calls, but answered with the previous verifier failure instead of listing runtime-recorded changed files.

Raw evidence:

- Qwen T20: `local/manual-testing/t91-dual-model-audit-expect-20260502-205601/TEST-OUTPUT-QWEN.txt`
- Gemma T20: `local/manual-testing/t91-dual-model-audit-expect-20260502-205601/TEST-OUTPUT-GEMMA.txt`
- Structured turn logs in `~/.talos/sessions/*.turns.jsonl` recorded successful mutating tool calls that the deterministic answer path did not read.

## Root Cause

`AssistantTurnExecutor.deterministicDirectAnswerIfNeeded` received only `messages` and `TaskContract`, then `verifiedFollowUpSummaryIfNeeded` scanned prior assistant text. It could not access `SessionMemory`, `TurnRecord.ToolCallSummary`, or other runtime-owned mutation facts.

This violated the T54/T59 design direction: `What did you change?` style answers should use previous verified outcome or trace state, not model memory or assistant prose alone.

## Implementation

- Added `ChangeSummaryContext`, a compact runtime-owned session ledger for successful mutating tool calls.
- Stored the ledger in `SessionMemory` and reset it on `clear()`.
- Updated `ActiveTaskContextUpdateListener` to record successful mutating tool path hints from post-turn audit data.
- Passed turn `Context` into `AssistantTurnExecutor` deterministic direct answers.
- Rendered changed-files follow-ups from runtime ledger data before falling back to prior assistant prose.
- Preserved outcome-dominance behavior for status follow-ups such as `did you make the changes?`.
- Kept the direct answer tool-free; no protected file reads, workspace scanner, vector memory, or broad memory feature was added.

## Acceptance Result

- Changed-files questions now prefer runtime-recorded mutating tool calls.
- Failed verification no longer erases the changed-file list.
- Unresolved expected targets and verifier findings can still be reported separately.
- No protected content is read or resurfaced by this path.
- No-tool turns do not overwrite a previous changed-files ledger.
- `/clear` resets the ledger with the rest of session memory.

## Tests

- `AssistantTurnExecutorTest.VerifiedFollowUpSummaries.changedFilesAuditQuestionPrefersRuntimeLedgerOverFailedVerifierProse`
- `ActiveTaskContextUpdateListenerTest.mutatingTurnUpdatesRuntimeChangeSummaryContext`
- `ActiveTaskContextUpdateListenerTest.noToolTurnDoesNotOverwriteExistingChangeSummaryContext`
- `ClearCommandTest.clearWithHistory`

## Verification

- `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest$VerifiedFollowUpSummaries" --tests "dev.talos.runtime.ActiveTaskContextUpdateListenerTest" --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat e2eTest --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest TalosBench summary:

- `local/manual-testing/talosbench/20260502-215250/summary.md`

Result: all runnable TalosBench cases passed; approval-sensitive cases remained `MANUAL_REQUIRED`; no failures.
