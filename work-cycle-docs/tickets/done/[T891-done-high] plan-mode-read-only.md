# [T891-done-high] Plan mode read-only

Status: done
Priority: high

## Evidence Summary

- Source: owner-approved Ask / Plan / Agent refactor plan
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 092fb752
- Verification status: focused green; implemented in this ticket

Plan mode is the middle public posture: it can inspect and produce an
implementation plan, but it must not apply changes, run commands, request
approval, or consume a pending mutation as an apply obligation.

## Goal

Add canonical `/mode plan` as a read-only planning mode over the existing
assistant pipeline, with plan-specific prompt rules and `PLAN_READ_ONLY`
capability posture.

## Non-Goals

- No approve-plan-then-execute handoff; that is deliberately future scope.
- No broad UX/docs sweep; T892 owns help/status/docs alignment.
- No trace canonicalization; T893 owns trace assertions.

## Architecture Metadata

Capability:

- read-only planning mode

Operation(s):

- route, prompt construction, native tool-surface selection

Owning package/class:

- `PlanMode`, `ModeController`, `SystemPromptBuilder`, plan prompt resource

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high (Plan must not expose mutation or command execution)
- Approval behavior: Plan must not request mutation/command approval
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: Plan mode focused tests
- Verification profile: focused unit tests
- Repair profile: n/a

Outcome and trace:

- Outcome/trace changes: prompt/capability frame should show read-only Plan posture;
  trace field cleanup comes later.

Refactor scope:

- `<allowed: PlanMode, plan prompt section, controller registration>`
- `<forbidden: apply-plan handoff, command execution, mutation tools>`

## Acceptance Criteria

- `/mode plan` is selectable and advertised.
- Plan uses `CapabilityPosture.PLAN_READ_ONLY`.
- Plan LLM requests expose no mutation tools and no command tools.
- Plan prompt rules tell the model to produce a concrete plan, not apply changes.
- Mutation-shaped prompts in Plan can result in a plan, but cannot mutate or request approval.

## Tests / Evidence

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.PlanModeTest" --tests "dev.talos.cli.modes.ModeControllerTest" --tests "dev.talos.core.llm.SystemPromptBuilderTest" --no-daemon
```

Result:

- Red: `PlanMode` and `SystemPromptBuilder.forPlan()` did not exist.
- Green: Plan mode, mode controller, and prompt-builder focused tests passed.

## Work-Test Cycle Notes

- Inner-loop ticket; no candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.
