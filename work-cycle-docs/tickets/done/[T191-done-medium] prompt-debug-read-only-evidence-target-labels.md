# T191 - Prompt Debug Read-Only Evidence Target Labels

Status: done
Severity: medium

## Problem

The T190 focused audit exposed misleading prompt-debug wording on read-only diagnostic turns.

Prompt debug could show:

`Expected-target coverage: MISSING`

on an inspection-only flow even when the visible trace proved the relevant public files were read and the runtime-owned diagnostic was correct.

This was misleading because `Expected-target coverage` is mutation-oriented. On read-only turns, parsed target paths are evidence hints, not mutation targets that must appear in an `[ExpectedTargets]` frame.

## Scope

In scope:

- Keep mutation-turn prompt debug behavior unchanged.
- Rename the target section for read-only turns to `Evidence target hints`.
- Report read-only frame coverage as `Evidence-target frame coverage: N/A (read-only task)` when there are evidence hints.
- Add a prompt-debug regression test.

Out of scope:

- Task contract changes.
- Prompt wording changes.
- Tool-loop behavior changes.

## Implementation

- Updated `PromptDebugInspector` to choose target labels from the task contract:
  - mutation turns: `Expected targets` and `Expected-target coverage`
  - read-only turns: `Evidence target hints` and `Evidence-target frame coverage`
- Read-only target coverage now returns `N/A (read-only task)` instead of `MISSING`.
- Added `PromptDebugCommandTest.readOnlyPromptDebugDoesNotReportMissingMutationTargetCoverage`.

## Verification

- Red test:
  - `.\gradlew.bat test --tests 'dev.talos.cli.repl.slash.PromptDebugCommandTest.readOnlyPromptDebugDoesNotReportMissingMutationTargetCoverage' --no-daemon`
- Green verification:
  - `.\gradlew.bat test --tests 'dev.talos.cli.repl.slash.PromptDebugCommandTest.readOnlyPromptDebugDoesNotReportMissingMutationTargetCoverage' --no-daemon`
  - `.\gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.runtime.verification.StaticTaskVerifierTest --tests dev.talos.cli.repl.slash.PromptDebugCommandTest --no-daemon`
  - `.\gradlew.bat test --no-daemon`
  - `.\gradlew.bat e2eTest --tests 'dev.talos.harness.JsonScenarioPackTest.readOnlyWebDiagnosticsShortCircuit' --no-daemon`
  - `.\gradlew.bat build --no-daemon`
  - `.\gradlew.bat installDist --no-daemon`

## Audit

Confirmed in:

`local/manual-testing/t190-focused-static-button-retry-audit-20260507-155901/FINDINGS-T190-FOCUSED-STATIC-BUTTON-RETRY.md`

The focused audit artifacts no longer show `Expected-target coverage: MISSING` for the read-only diagnostic flow.
