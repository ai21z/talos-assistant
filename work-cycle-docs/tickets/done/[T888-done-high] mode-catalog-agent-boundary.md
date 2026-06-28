# [T888-done-high] Mode catalog, canonical Agent, and hard explicit boundaries

Status: done
Priority: high

## Evidence Summary

- Source: owner-approved Ask / Plan / Agent refactor plan
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 5da28521
- Verification status: implemented and focused tests passed

Current mode routing exposed `auto`, `dev`, `rag`, `ask`, and `unified` as the
public surface. Explicit mode routing fell back to a sweep over every mode, `web`
could still handle non-empty input if reached, and auto structural command dispatch
was string-keyed through `byName.get("dev")`.

## Goal

Introduce the mode catalog foundation for the new public surface:
`auto`, `ask`, and canonical `agent` now, with `plan` added by T891. Legacy
aliases should resolve to canonical display names, explicit mode selection must be
a hard boundary, and structural commands must remain deterministic without relying
on the public `dev` name.

## Non-Goals

- No Ask read-only enforcement yet; that belongs to T889/T890.
- No Plan mode yet; that belongs to T891.
- No docs/help/banner sweep yet; that belongs to T892.
- No trace mode fix yet; that belongs to T893.

## Architecture Metadata

Capability:

- REPL mode catalog and router semantics

Operation(s):

- route, inspect, display

Owning package/class:

- `dev.talos.cli.modes.ModeController`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high (mode routing is a trust boundary)
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: focused router and slash-command tests
- Verification profile: mode catalog and routing tests
- Repair profile: n/a

Outcome and trace:

- Outcome/trace changes: none in this ticket; T893 records canonical mode in trace

Refactor scope:

- `<allowed: mode catalog metadata, alias resolution, explicit boundary, structural command handler extraction>`
- `<forbidden: read-only posture enforcement, Plan mode, trace persistence, broad prompt rewrite>`

## Acceptance Criteria

- `/mode agent` is accepted and displays canonical `agent`.
- Legacy `chat`, `dev`, and `unified` aliases resolve to canonical `agent`.
- `rag` remains selectable as a hidden legacy mode but is not advertised.
- `/mode` advertises `auto`, `ask`, and `agent` for this ticket; `plan` is added
  later by T891.
- Explicit mode routing never sweeps into another mode.
- Reserved `web` cannot be selected or reached through routing.
- Auto structural `ls/show/open` dispatch remains deterministic through a
  structural handler, not the public `dev` alias.

## Tests / Evidence

Focused tests passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ModeControllerTest" --tests "dev.talos.cli.repl.slash.SimpleCommandsTest" --no-daemon
```

## Closeout

Implemented a catalog-backed `ModeController` with canonical names, hidden aliases,
reserved entries, and a direct structural command handler. `UnifiedAssistantMode`
is now canonical `agent`, while `chat`, `dev`, and `unified` resolve to it.
`RagMode` remains hidden/selectable, `WebMode` no longer handles input, and
explicit selected modes no longer sweep into other modes.
