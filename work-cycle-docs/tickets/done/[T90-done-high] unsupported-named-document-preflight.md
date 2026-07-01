# T90 - Unsupported Named Document Preflight

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Closed: 2026-05-02

## Source

- T61-C milestone QA findings: `local/manual-testing/t61-c-milestone-qa-20260502-155141/FINDINGS-T61-C.md`
- T61-C milestone QA summary: `local/manual-testing/t61-c-milestone-qa-20260502-155141/SUMMARY-T61-C.md`
- Full run trace: `trc-66c8a8d2-e6b5-4d2f-a0c2-75649c6a2447`
- Focused rerun trace: `trc-9a81963d-9f16-487e-9b16-bbc8417ceb13`

## Problem

For the prompt `Summarize report.docx.`, the contract correctly derived `UNSUPPORTED_CAPABILITY_CHECK_REQUIRED` with expected target `report.docx`, and the prompt-side tool surface exposed only `talos.read_file`. In the full run, however, the model still drifted into unrelated workspace reads (`README.md`, `notes.md`) before attempting the named unsupported target.

Runtime answer containment prevented a false summary, but the tool sequence was still wrong. Unsupported named document turns should attempt the named unsupported target deterministically, or stop without reading unrelated files first.

## Implementation

- Added a runtime-owned unsupported capability preflight in `AssistantTurnExecutor`.
- The preflight runs before the model LLM/tool loop only when the selected evidence obligation is `UNSUPPORTED_CAPABILITY_CHECK_REQUIRED` and all expected targets are unsupported document formats.
- The preflight synthesizes the existing `talos.read_file` handoff for the named unsupported target, preserving normal tool-loop auditing, sandbox checks, unsupported-format errors, and protected-read permission policy.
- Mixed expected targets are intentionally not preflighted, preserving explicit converted fallback behavior such as `If report.docx is unsupported, read report.txt instead.`
- Added an executor regression proving a drifting scripted model cannot read unrelated `README.md` or `notes.md` before the unsupported target.
- Added TalosBench case `t90-unsupported-docx-mixed-workspace-preflight` to guard the live mixed-workspace prompt shape.

## Acceptance Evidence

- `Summarize report.docx.` preflights `talos.read_file -> report.docx`.
- The final answer reports the unsupported document capability boundary.
- A drifting scripted model's unrelated `talos.list_dir`, `talos.read_file -> README.md`, and `talos.read_file -> notes.md` calls are not executed.
- Existing explicit converted fallback e2e coverage remains green.
- Live TalosBench T90 case passes with `Tool calls: 1`, `UNSUPPORTED_CAPABILITY_CHECK_REQUIRED`, and no unrelated file markers.

## Verification

- `.\gradlew.bat test --tests "*unsupportedOnlyNamedTargetPreflightsBeforeDriftingModelReads" --no-daemon` - PASS
- `.\gradlew.bat test --tests "*unsupportedDocxReadReportsCapabilityWithoutClaimingSummary" --tests "*unsupportedOnlyNamedTargetPreflightsBeforeDriftingModelReads" --no-daemon` - PASS
- `.\gradlew.bat e2eTest --tests dev.talos.harness.JsonScenarioPackTest.unsupportedDocxStopsBeforeSpeculativeFallbacks --tests dev.talos.harness.JsonScenarioPackTest.unsupportedDocxAllowsExplicitConvertedTarget --no-daemon` - PASS
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - PASS, 32 cases validated
- `.\gradlew.bat test --no-daemon` - PASS
- `.\gradlew.bat e2eTest --no-daemon` - PASS
- `.\gradlew.bat installDist --no-daemon` - PASS
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId t90-unsupported-docx-mixed-workspace-preflight` - PASS
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat` - PASS for all runnable cases; approval-sensitive cases remain `MANUAL_REQUIRED`

## Follow-Up

- None for this ticket. The next full T61-style manual audit should still include unsupported document turns in mixed workspaces to confirm behavior across real model variance.
