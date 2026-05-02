# [T85-done-medium] Directory Listing Retry Containment

Status: done
Priority: medium
Date: 2026-05-02
Closed: 2026-05-02

## Source

This issue was exposed during full TalosBench verification after T83/T84.

The `simple-folder-listing` case briefly regressed because a successful
directory listing was followed by an unnecessary read attempt. The read was
blocked, but TalosBench correctly treats any file-content read attempt during a
filename-only listing request as a blocker.

## Problem

After the T84 verifier discovery change, small workspaces with `index.html`
could look like they had obvious primary files. That is useful for web repair
verification, but it must not cause directory-listing turns to run an inspection
retry after `talos.list_dir` has already satisfied the user request.

The tool loop also lacked a deterministic terminal answer for the successful
directory-listing shape, leaving room for a model reprompt to ask for extra
file reads.

## Goal

Directory-listing turns should stop after successful `talos.list_dir` evidence
and return file names only. They must not trigger primary-file inspection retry
or file-content reads.

## Non-Goals

- Do not change explicit read-file behavior.
- Do not hide failed directory-listing outcomes.
- Do not relax protected path policy.

## Changes

- Added a `DIRECTORY_LISTING` guard so inspect-completeness retry does not run
  primary-file inspection after a list-only request.
- Added a deterministic `ToolCallRepromptStage` terminal answer for successful
  `talos.list_dir` evidence:
  `Directory entries:` followed by the returned entry names.
- The deterministic terminal path canonicalizes accepted `list_dir` aliases.
- Added regressions for both the executor retry path and the tool-loop reprompt
  path.

## TDD Evidence

Red:

- `.\gradlew.bat test --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest.directoryListingStopsAfterSuccessfulListDir --no-daemon`
- `.\gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest.directoryListingDoesNotTriggerPrimaryFileInspectionRetry --no-daemon`
- The first failed because the loop wanted another reprompt; the second failed
  because the directory listing path could invoke an inspection retry.

Green:

- Both targeted tests passed after the directory-listing containment changes.

## Verification

- `.\gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest.directoryListingDoesNotTriggerPrimaryFileInspectionRetry --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest.directoryListingStopsAfterSuccessfulListDir --tests dev.talos.cli.modes.ExecutionOutcomeTest.streamingNoToolDirectAnswerOnlyMethodologyIsNotUngrounded --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesSiblingWebSurfaceDespiteReadme --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId simple-folder-listing,t57-read-config-requires-evidence,t68-no-inspection-methodology-direct-answer`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest full TalosBench summary:

- `local/manual-testing/talosbench/20260502-134135/summary.md`
- `simple-folder-listing`, `t57-read-config-requires-evidence`, and
  `t68-no-inspection-methodology-direct-answer` all passed.
