# T133 - AssistantTurnExecutor Decomposition Phase 1

Severity: high
Status: open

## Problem

`AssistantTurnExecutor` is the main god-class risk in Talos. It currently owns too much of the turn flow and is too large to keep absorbing new capability logic.

The goal is not a big-bang rewrite. The goal is one behavior-preserving extraction at a stable capability boundary.

## Scope

- Extract one focused service from `AssistantTurnExecutor`, choosing the safest boundary available after T123-T132 work:
  - `TurnPlanner`
  - `EvidenceGate`
  - `OutcomeRenderer`
  - `ToolSurfacePlanner`
- Preserve existing behavior.
- Add focused tests for the extracted service.
- Document the next extraction seam.

## Acceptance

- No behavior regression.
- Extracted service has a narrow public API and clear ownership.
- `AssistantTurnExecutor` loses meaningful responsibility, not just line count.
- Existing unit/e2e tests pass.
- Refactor follows the architecture guardrails from T126.

## Non-Goals

- No full executor rewrite.
- No new user-visible feature.
- No broad package reshuffle.

## Verification

- Focused tests for the extracted service.
- Existing nearby tests.
- `.\gradlew.bat --no-daemon build installDist`.
