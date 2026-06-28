# [T893-done-high] Canonical mode trace auditability

Status: done
Priority: high

## Evidence Summary

- Source: owner-approved Ask / Plan / Agent refactor plan
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 4ef1ff68
- Verification status: focused green; implemented in this ticket

`TurnProcessor` currently starts every local turn trace with mode `"unknown"`.
That makes `/last trace`, saved local traces, and prompt-debug-adjacent evidence
weaker than the runtime state: after the mode refactor, Ask/Plan/Agent posture
must be audit-visible.

## Goal

Record the canonical active mode in each local turn trace so audit evidence can
distinguish `auto`, `ask`, `plan`, `agent`, hidden legacy `rag`, and legacy
aliases that resolve to canonical `agent`.

## Non-Goals

- No E2E scenario-harness mode parameter; T894 owns that.
- No change to approval policy, tool execution, or mode routing.
- No removal of legacy aliases.

## Architecture Metadata

Capability:

- local trace/auditability

Operation(s):

- turn processing, trace start metadata, mode-controller trace label

Owning package/class:

- `TurnRouter`, `TurnProcessor`, `ModeController`, local trace tests

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high (trace evidence must not hide Ask/Plan mode posture)
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: focused trace tests
- Verification profile: focused unit tests plus ticket hygiene
- Repair profile: n/a

Outcome and trace:

- Outcome/trace changes: local trace `mode` changes from `"unknown"` to the
  canonical active mode reported by the turn router.

Refactor scope:

- `<allowed: TurnRouter trace-label seam, TurnProcessor trace mode metadata, mode trace tests>`
- `<forbidden: E2E harness expansion, prompt construction, tool-surface policy>`

## Acceptance Criteria

- Local trace mode is never `"unknown"` for `ModeController`-routed turns.
- Auto-mode turns record `auto`.
- Explicit Ask records `ask`.
- Explicit Plan records `plan`.
- Explicit Agent records `agent`.
- Legacy `dev`, `chat`, and `unified` aliases record canonical `agent`.
- Hidden legacy `rag` records `rag`.
- Prompt-debug/local trace evidence continues to carry capped Ask/Plan tool
  surfaces from the prior posture work.

## Tests / Evidence

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest" --tests "dev.talos.cli.repl.ReplRouterTraceTest" --tests "dev.talos.cli.modes.ModeControllerTest" --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
```

Result:

- Red: `TurnProcessorTest` showed local trace mode remained `"unknown"` for
  `ModeController` turns, and `ReplRouterTraceTest` did not render a mode line.
- Green: trace-focused processor/router tests passed with canonical mode labels
  for auto, Ask, Plan, Agent, Agent aliases, and hidden RAG.

## Work-Test Cycle Notes

- Inner-loop ticket; no candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.
