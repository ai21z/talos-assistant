# [T890-done-high] Ask mode read-only

Status: done
Priority: high

## Evidence Summary

- Source: owner-approved Ask / Plan / Agent refactor plan
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / dd11f980
- Verification status: focused green; implemented in this ticket

Ask currently reads as the public read-only mode but its historical prompt rules
advertise file creation/editing. T889 added the runtime capability posture
foundation; this ticket wires Ask through that posture and adds a deterministic
mutation nudge before the model can attempt a write.

## Goal

Make `/mode ask` a true read-only ceiling: no mutation tools, no command tools,
no approval prompts, and no `BLOCKED_BY_POLICY` outcome for direct mutation
requests.

## Non-Goals

- No Plan mode yet; T891 owns Plan behavior.
- No global mode/docs sweep; T892 owns public UX alignment.
- No trace-mode canonicalization; T893 owns trace assertions.

## Architecture Metadata

Capability:

- read-only Ask mode

Operation(s):

- route, prompt construction, native tool-surface selection

Owning package/class:

- `AskMode`, `SystemPromptBuilder` resources, `AssistantTurnExecutor`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high (Ask must not expose mutation or command execution)
- Approval behavior: direct mutation request in Ask must not request approval
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: Ask mode focused tests
- Verification profile: focused unit tests
- Repair profile: n/a

Outcome and trace:

- Outcome/trace changes: deterministic nudge instead of policy-blocked mutation outcome

Refactor scope:

- `<allowed: AskMode option wiring, Ask prompt rules, deterministic mutation nudge>`
- `<forbidden: PlanMode, route catalog/docs sweep, trace schema changes>`

## Acceptance Criteria

- Ask mutation requests return: `Ask is read-only; switch to \`/mode agent\` to make changes.`
- The deterministic Ask mutation nudge does not call the LLM.
- Ask LLM requests use `CapabilityPosture.ASK_READ_ONLY`.
- Ask native tools expose no mutation tools and no command tools.
- Ask prompt text does not advertise write/edit capability.
- Ask mutation requests do not trigger approval or `BLOCKED_BY_POLICY`.

## Tests / Evidence

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AskModeTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.SystemPromptBuilderTest" --tests "dev.talos.cli.modes.AskModeTest" --no-daemon
```

Result:

- Red: `AskMode.READ_ONLY_MUTATION_NUDGE` did not exist.
- Green: Ask mode and prompt-builder focused tests passed.

## Work-Test Cycle Notes

- Inner-loop ticket; no candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.
