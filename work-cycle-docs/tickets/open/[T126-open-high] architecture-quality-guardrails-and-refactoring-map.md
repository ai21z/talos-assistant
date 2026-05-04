# T126 - Architecture Quality Guardrails And Refactoring Map

Severity: high
Status: open

## Problem

The capability roadmap needs explicit engineering design rules before the tool surface grows. The current code already has useful policy objects and records, but large services show coupling pressure.

Largest local pressure points include:

- `AssistantTurnExecutor.java` at about 3370 lines.
- `ExecutionOutcome.java` at about 1154 lines.
- `StaticTaskVerifier.java` at about 1170 lines.
- `TurnProcessor.java` at about 871 lines.

Without guardrails, new tools can recreate the current god-class problem.

## Evidence

- `docs/superpowers/specs/2026-05-04-talos-capability-spine-workspace-architecture-design.md`
- Local source line counts gathered during the architecture review.

## Scope

- Add a durable architecture/refactoring map for capability growth.
- Define package ownership and dependency direction rules.
- Define when to use ports/adapters, policy objects, command pattern, strategy profiles, immutable records, and side-effect boundaries.
- Update ticket template or workflow guidance so new tool tickets include capability, risk, approval, checkpoint, verification, trace, and ownership notes.

## Acceptance

- A written architecture/refactoring map is committed.
- The map names the first extraction seams from `AssistantTurnExecutor`.
- The map identifies which refactors are allowed with each capability ticket and which broad rewrites are forbidden.
- Ticket guidance requires architecture metadata for future tool/capability tickets.
- No behavior-changing refactor is performed in this ticket.

## Non-Goals

- No large code movement.
- No new tools.
- No Java baseline change.

## Verification

- Documentation review.
- `git diff --check`.
- If ticket templates are changed, verify formatting and links.
