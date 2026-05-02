# [T83-done-medium] Direct-Answer Grounding Warning Suppression

Status: done
Priority: medium
Date: 2026-05-02
Closed: 2026-05-02

## Source

Follow-up from the T82 focused audit:

- Summary: `local/manual-testing/t82-focused-audit-20260502-124432/SUMMARY-T82-FOCUSED.md`
- Transcript: `local/manual-testing/t82-focused-audit-20260502-124432/TEST-OUTPUT-T82-FOCUSED.txt`

Finding F1 showed that this prompt was correctly classified as
`SMALL_TALK` / `DIRECT_ANSWER_ONLY`, but the trace still recorded an
ungrounded workspace warning:

`Without inspecting the workspace, explain how you would review a Java CLI project.`

## Problem

The task-contract layer correctly honored the user's no-inspection instruction,
but the no-tool grounding annotation layer still treated the wording as an
evidence request. That turned a clean direct-answer turn into an advisory
outcome in trace/debug surfaces.

## Goal

Direct-answer-only turns must not receive workspace-grounding warnings merely
because the user mentions review, inspect, files, or project methodology while
also explicitly saying not to inspect the workspace.

## Non-Goals

- Do not weaken grounding warnings for real workspace evidence requests.
- Do not change task classification.
- Do not expose tools for direct-answer-only turns.

## Changes

- Added a direct-answer guard to the streaming no-tool grounding annotation
  path.
- Added the same guard to the non-streaming grounding retry path.
- Added outcome-layer regression coverage for the exact audited prompt.

## TDD Evidence

Red:

- `.\gradlew.bat test --tests dev.talos.cli.modes.ExecutionOutcomeTest.streamingNoToolDirectAnswerOnlyMethodologyIsNotUngrounded --no-daemon`
- Failed because the audited prompt still produced an advisory grounding
  warning.

Green:

- The same targeted test passed after the direct-answer guard.

## Verification

- `.\gradlew.bat test --tests dev.talos.cli.modes.ExecutionOutcomeTest.streamingNoToolDirectAnswerOnlyMethodologyIsNotUngrounded --tests dev.talos.cli.modes.ExecutionOutcomeTest.streamingNoToolEvidenceAnswerIsAdvisoryAndUngrounded --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesSiblingWebSurfaceDespiteReadme --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId t68-no-inspection-methodology-direct-answer`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest full TalosBench summary:

- `local/manual-testing/talosbench/20260502-134135/summary.md`

Focused installed audit:

- Transcript:
  `local/manual-testing/t83-t84-focused-audit-20260502-131145/TEST-OUTPUT-T83-T84-FOCUSED.txt`
- No `Grounding check` text appeared for the direct-answer no-inspection turn.
- Trace recorded `SMALL_TALK`, `DIRECT_ANSWER_ONLY`, and clean completion.
