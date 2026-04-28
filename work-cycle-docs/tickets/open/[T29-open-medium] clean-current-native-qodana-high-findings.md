# [T29-open-medium] Ticket: Clean Current Native Qodana High Findings
Date: 2026-04-28
Priority: medium
Status: open
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

## Known Risks

- Qodana native mode writes SARIF only; that is acceptable if provenance matches
  the current candidate.
- Removing defensive null checks without understanding caller contracts can
  make real edge cases harder to diagnose.
