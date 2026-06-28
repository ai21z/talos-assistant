# [T889-done-high] Capability posture foundation

Status: done
Priority: high

## Evidence Summary

- Source: owner-approved Ask / Plan / Agent refactor plan
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 0e573c9b
- Verification status: focused green; implemented in this ticket

Ask and Plan cannot be implemented as labels or prompt wording. The shared
assistant pipeline needs an explicit capability posture that caps the effective
task contract, execution phase, native tool surface, and prompt-visible tool list
before the model sees the turn.

## Goal

Add a shared `CapabilityPosture` foundation so modes can request Agent behavior
or read-only Ask/Plan behavior without duplicating the executor.

## Non-Goals

- No AskMode wiring yet; T890 uses this foundation.
- No PlanMode yet; T891 uses this foundation.
- No UX/docs/trace sweep yet.

## Architecture Metadata

Capability:

- current-turn capability posture

Operation(s):

- inspect, route, prompt construction

Owning package/class:

- `AssistantTurnExecutor`, `AssistantTurnPreparation`, runtime policy helpers

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high (tool surface and prompt frame are trust boundaries)
- Approval behavior: read-only posture must not request mutation approval
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: executor/tool-surface tests
- Verification profile: focused unit tests
- Repair profile: n/a

Outcome and trace:

- Outcome/trace changes: capability frame changes; trace field changes come later

Refactor scope:

- `<allowed: posture enum/policy, executor option, preparation capping>`
- `<forbidden: broad ToolSurfacePlanner rewrite, Ask/Plan mode behavior changes>`

## Acceptance Criteria

- `CapabilityPosture.AGENT` preserves current behavior.
- `ASK_READ_ONLY` and `PLAN_READ_ONLY` force mutation-shaped turns to
  `mutationAllowed=false`, `phase=INSPECT`, and no mutating native tools.
- Prompt-visible tools and native tool request names are derived from the same
  capped surface.
- Read-only posture does not expose command/verification command tools.

## Tests / Evidence

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorPhasePolicyTest" --no-daemon
```

Result:

- Red: compile failed before `CapabilityPosture` existed.
- Green: focused native-tool surface + phase-policy tests passed.

## Work-Test Cycle Notes

- Inner-loop ticket; no candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.
