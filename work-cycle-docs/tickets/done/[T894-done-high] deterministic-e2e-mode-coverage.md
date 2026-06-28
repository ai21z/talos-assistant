# [T894-done-high] Deterministic E2E mode coverage

Status: done
Priority: high

## Evidence Summary

- Source: owner-approved Ask / Plan / Agent refactor plan
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 29354977
- Verification status: focused green; implemented in this ticket

The mode refactor had unit coverage for the catalog, posture caps, Ask, Plan,
and local trace mode metadata, but the deterministic E2E scenario harness could
not yet select a mode and had no route-through seam for `TurnProcessor`. That
left a gap: scenarios could exercise the raw tool loop or executor, but not the
actual public mode router used by the REPL.

## Goal

Add deterministic E2E support for selecting `auto`, `ask`, `plan`, `agent`, and
legacy aliases in scenario definitions, then cover the public routing invariants
through `TurnProcessor`.

## Non-Goals

- No production runtime behavior change.
- No replacement of existing raw `ToolCallLoop` or direct executor scenario
  paths.
- No approve-plan-then-execute handoff.
- No live model audit or installed-product evidence; the end-of-arc gate owns
  that separately.

## Architecture Metadata

Capability:

- deterministic E2E harness coverage

Operation(s):

- scenario definition parsing, JSON loading, mode route-through testing

Owning package/class:

- `ScenarioDefinition`, `JsonScenarioLoader`, `ScenarioRunner`,
  `TurnProcessorScenarioResult`, `ModeScenarioTest`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high (mode safety invariants need route-level regression coverage)
- Approval behavior: test harness records deterministic approval counts only
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: focused E2E scenarios plus ticket hygiene
- Verification profile: focused `e2eTest` route-through scenarios
- Repair profile: n/a

Outcome and trace:

- Outcome/trace changes: no production trace schema change; route-through
  scenarios now assert canonical local trace mode.

Refactor scope:

- `<allowed: E2E scenario mode field, JSON loader parse, TurnProcessor harness seam, route-level mode scenarios>`
- `<forbidden: production mode routing changes, prompt text changes, tool policy changes>`

## Acceptance Criteria

- Scenario definitions default to `auto` mode.
- JSON scenarios may opt into a mode through a `mode` field.
- The E2E harness can run through `TurnProcessor`, not only raw tool loop or
  direct executor seams.
- Ask mutation requests refuse locally without approvals, mutation, or
  `BLOCKED_BY_POLICY`.
- Plan mutation requests stay read-only, expose no mutation/command tools, and
  do not create files.
- Agent mode can execute an approved mutation through the route-through seam.
- Auto mode keeps structural commands on the deterministic handler.
- Legacy `dev`, `chat`, and `unified` aliases resolve to canonical `agent`.

## Tests / Evidence

Focused tests:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.ModeScenarioTest" --tests "dev.talos.harness.JsonScenarioPackTest.readOnlyRepoQuestion" --tests "dev.talos.harness.JsonScenarioPackTest.jsonScenarioCanSelectMode" --no-daemon
```

Result:

- Red: compile failed because `ScenarioDefinition.mode()`,
  `ScenarioDefinition.Builder.withMode(...)`, and
  `ScenarioRunner.runThroughTurnProcessor(...)` did not exist.
- Green: route-through mode scenarios and JSON mode parsing passed.

## Work-Test Cycle Notes

- Inner-loop ticket; no candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.
