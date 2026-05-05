# T133 - AssistantTurnExecutor Decomposition Phase 1

Severity: high
Status: done

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

- Red focused test first failed on missing `EvidenceGate`.
- Focused service test passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.runtime.policy.EvidenceGateTest`
- Nearby executor/outcome suite passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.runtime.policy.EvidenceGateTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.cli.modes.ExecutionOutcomeTest --tests dev.talos.cli.modes.OutcomeDominancePolicyTest --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest`
- Full verification passed:
  `.\gradlew.bat --no-daemon build installDist`

## Completion Notes

- Extracted `EvidenceGate` from `AssistantTurnExecutor`.
- `EvidenceGate` now owns pure evidence-obligation decisions:
  selected obligation, read-evidence handoff requirement, protected target filtering, explicit protected-read intent, and unsupported-document target selection.
- `AssistantTurnExecutor` still orchestrates the model/tool handoff but no longer owns the policy heuristics.

## Next Extraction Seam

The next high-value seam is outcome rendering. `ExecutionOutcome` still calls several static helper methods on `AssistantTurnExecutor`, so a follow-up should move those helpers behind an `OutcomeRenderer` or equivalent runtime-owned service without changing final-answer policy.
