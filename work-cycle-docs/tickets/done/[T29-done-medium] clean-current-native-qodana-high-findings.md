# [T29-done-medium] Ticket: Clean Current Native Qodana High Findings
Date: 2026-04-28
Priority: medium
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/work-test-cycle-step-by-step.md`

## Context

Candidate 0.9.6 has current native Qodana evidence using:

```powershell
./gradlew.bat qodanaNativeFreshLocal --no-daemon
./gradlew.bat talosQualitySummaries --no-daemon
```

The summary matches `v0.9.0-beta-dev` at merge commit `2a00e1a`, with 4 high
findings and 0 critical findings. These findings are cleanup work, not a
blocker for the Execution Discipline and Local Trust Infrastructure milestone.

Known current findings:

- `AssistantTurnExecutor.java:1298`: `contract == null` is always false
- `AssistantTurnExecutor.java:1459`: `retryContract == null` is always false
- `UnifiedAssistantMode.java:118`: `size` invocation may produce
  `NullPointerException`
- `StaticVerificationRepairContext.java:119`: `rawLine == null` is always false

## Goal

Clean or justify the current native Qodana high findings without changing
runtime behavior.

## Non-Goals

- Do not start policy extraction.
- Do not change Qodana configuration unless a finding proves the configuration
  is wrong.
- Do not lower inspection severity or hide findings.
- Do not bump the version or update `CHANGELOG.md` unless this becomes part of
  a later versioned candidate.

## Implementation Notes

- Remove provably dead null checks only when the called methods guarantee
  non-null values.
- Guard or prove safe the possible `UnifiedAssistantMode` NPE.
- Keep changes small and behavior-preserving.
- If a finding is a false positive, document the reasoning in the ticket and in
  a narrow code comment only if that comment prevents future confusion.

## Acceptance Criteria

- Provably dead null checks in `AssistantTurnExecutor` and
  `StaticVerificationRepairContext` are removed or justified.
- The possible `UnifiedAssistantMode` NPE is guarded or proven safe.
- `./gradlew.bat test --no-daemon` passes.
- `./gradlew.bat qodanaNativeFreshLocal --no-daemon` runs.
- `./gradlew.bat talosQualitySummaries --no-daemon` runs.
- `qodana-summary.json` still matches the current branch and revision.
- `highIssues` decreases, or remaining findings are explicitly documented as
  accepted/false-positive with rationale.

## Tests / Evidence

Run:

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat qodanaNativeFreshLocal --no-daemon
./gradlew.bat talosQualitySummaries --no-daemon
```

Inspect:

```powershell
Get-Content build/reports/talos/qodana-summary.json
```

## Work-Test Cycle Notes

Use the inner dev loop. Do not declare a versioned candidate for this cleanup
unless explicitly requested.

## Current Code Read

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/runtime/verification/StaticVerificationRepairContext.java`

Initial read on 2026-04-29 shows the old `StaticVerificationRepairContext`
`rawLine == null` finding is likely stale after T39 because repair context now
delegates to `RepairPolicy` and no longer accepts `rawLine`.

## Planned Evidence

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat qodanaNativeFreshLocal --no-daemon
./gradlew.bat talosQualitySummaries --no-daemon
```

## Implementation Summary

- Removed dead null checks that Qodana proved unreachable in
  `AssistantTurnExecutor`, checkpoint config parsing, permission config
  parsing, checkpoint target extraction, and repair problem extraction.
- Normalized `UnifiedAssistantMode` history to a non-null list before prompt
  capture, removing the possible `history.size()` null dereference.
- Replaced an `Optional<LocalTurnTrace>` parameter in
  `ExplainLastTurnCommand.renderTrace` with a nullable internal argument while
  keeping `loadLocalTrace` as the optional-returning seam.
- Simplified permission remember eligibility after the destructive-risk branch
  already handled destructive calls.
- Added a narrow resource suppression in `TurnProcessor.process` because the
  context-owned `LlmClient` is borrowed for model metadata and must not be
  closed per turn.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests / Evidence Run

```powershell
./gradlew.bat test --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat qodanaNativeFreshLocal --no-daemon
```

Result: PASS. Fresh enabled-profile Qodana findings decreased from 11 high
findings to 0 applied-profile findings.

```powershell
./gradlew.bat talosQualitySummaries --no-daemon
```

Result: PASS. `build/reports/talos/qodana-summary.json` reported:

- `summaryStatus`: `qodana-results-match-current-candidate`
- `totalIssues`: 0
- `highIssues`: 0
- `criticalIssues`: 0

Qodana still printed suggested inspections and JetBrains IDE diagnostic noise
outside the enabled profile, but those were not counted in the SARIF-backed
Talos Qodana summary.

```powershell
./gradlew.bat check --no-daemon
```

Result: PASS. Run as an extra safety gate because the cleanup touched runtime
classes across trace, permission, checkpoint, and repair code.

## Manual Talos Check Result

Not required. T29 is static-analysis cleanup with no intended runtime behavior
change.

## Known Follow-Ups

- None for the enabled Qodana profile. Future candidates should continue using
  `qodanaNativeFreshLocal` followed by `talosQualitySummaries` to avoid stale
  Qodana evidence.

## Known Risks

- Qodana native mode writes SARIF only; that is acceptable if provenance matches
  the current candidate.
- Removing defensive null checks without understanding caller contracts can
  make real edge cases harder to diagnose.
