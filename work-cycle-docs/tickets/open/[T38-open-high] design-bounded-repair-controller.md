# [T38-open-high] Ticket: Design Bounded Repair Controller
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`

## Context

0.9.6 can classify repair intent, expose tools correctly, ask approval, verify
static web tasks, and report incomplete outcomes truthfully. It still lacks a
dedicated repair controller for post-verification failure and invalid edit
loops.

## Goal

Design a dedicated bounded repair controller/policy.

## Non-Goals

- Do not implement repair control in this ticket.
- Do not add a planner or multi-agent repair system.
- Do not add shell/browser execution.
- Do not weaken approval, permission, or checkpoint requirements.

## Implementation Notes

The design must define:

- `RepairPlan`
- reread-before-retry rules
- max attempts
- stop conditions
- verifier finding input
- invalid edit loop handling
- downgrade-to-partial behavior
- relation to `StaticVerificationRepairContext`
- relation to `ToolCallLoop`
- relation to trace and checkpoint

## Acceptance Criteria

- Repair controller design document exists.
- Design defines `RepairPlan`.
- Design defines reread-before-retry rules.
- Design defines max attempts and no-progress stop conditions.
- Design defines how verifier findings become repair input.
- Design defines truthful downgrade behavior when repair fails.
- Design defines tests for failed static web verification and invalid edit
  retry.
- No runtime implementation is included.

## Tests / Evidence

Run:

```powershell
./gradlew.bat test --no-daemon
```

## Work-Test Cycle Notes

Design-only ticket. This should happen after trace and permission foundations
are clearer.

## Known Risks

- Repair control can become a planner if not bounded.
- Over-aggressive repair can mutate files beyond the user's intended scope.
