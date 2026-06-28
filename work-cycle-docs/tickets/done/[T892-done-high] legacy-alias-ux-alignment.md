# [T892-done-high] Legacy alias and UX alignment

Status: done
Priority: high

## Evidence Summary

- Source: owner-approved Ask / Plan / Agent refactor plan
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / bb33e361
- Verification status: focused green; implemented in this ticket

The mode runtime now has canonical `agent`, read-only `ask`, and read-only
`plan`, but several user-facing surfaces can still lag the runtime catalog. This
ticket aligns help, prompt inspection, status-visible naming, and user docs so
the public surface is `auto`, `ask`, `plan`, and `agent`, with legacy aliases
accepted but hidden.

## Goal

Make the visible mode surface consistent with the new catalog: advertise only
`auto`, `ask`, `plan`, and `agent`; keep `dev`, `chat`, and `unified` as hidden
aliases to canonical `agent`; keep `rag` hidden but functional; and keep `web`
reserved and rejected.

## Non-Goals

- No trace-mode canonicalization; T893 owns `/last trace` assertions.
- No deterministic E2E harness expansion; T894 owns scenario mode coverage.
- No removal of legacy aliases.
- No behavior change to hidden `rag` beyond keeping it unadvertised.

## Architecture Metadata

Capability:

- mode catalog UX alignment

Operation(s):

- slash command rendering, prompt inspection, documentation

Owning package/class:

- `ModeCommand`, `HelpCommand`, `StatusCommand`, `PromptInspector`, user docs

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high (mode naming must not imply unavailable write capability)
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: focused command and prompt-inspector tests
- Verification profile: focused unit tests plus ticket hygiene
- Repair profile: n/a

Outcome and trace:

- Outcome/trace changes: `/prompt` should show canonical mode names and capped
  read-only tool surfaces; trace canonicalization comes later in T893.

Refactor scope:

- `<allowed: help/mode/status/prompt/docs UX text and prompt-inspector canonicalization>`
- `<forbidden: trace storage changes, E2E harness changes, alias removal>`

## Acceptance Criteria

- `/mode` and `/help all` advertise only `auto`, `ask`, `plan`, and `agent`
  among public modes, and mark `web` as reserved.
- `/mode dev`, `/mode chat`, and `/mode unified` remain accepted but display as
  canonical `agent`.
- Hidden `rag` remains selectable but is not advertised in the public mode list.
- `/prompt` reports canonical `agent` for `dev`/`chat`/`unified` and applies the
  Ask/Plan read-only tool posture to prompt-visible tools.
- README and user first-run docs describe `auto`, `ask`, `plan`, and `agent`,
  with legacy aliases and `rag` as compatibility surfaces rather than primary
  modes.

## Tests / Evidence

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptInspectorTest" --tests "dev.talos.cli.repl.slash.SimpleCommandsTest" --tests "dev.talos.cli.modes.ModeControllerTest" --tests "dev.talos.cli.repl.ReplRouterRouteHintTest" --tests "dev.talos.cli.ui.StatusRowPresenterTest" --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
```

Result:

- Red: `PromptInspectorTest` still resolved `auto`/aliases to `unified` and
  did not apply Ask/Plan read-only posture to prompt-visible tools.
- Green: prompt-inspector, simple-command, mode-controller, route-hint, and
  status-row focused tests passed.

## Work-Test Cycle Notes

- Inner-loop ticket; no candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.
