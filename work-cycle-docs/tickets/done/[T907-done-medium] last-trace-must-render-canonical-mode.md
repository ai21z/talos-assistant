# [T907-done-medium] Last trace must render canonical mode

Status: done
Priority: medium

## Evidence Summary

- Source: installed-product manual audit
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / c3ff55c9
- Installed build: 2026-06-28T20:44:48.560965600Z
- Model/backend: llama_cpp / qwen2.5-coder-14b
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\talos-mode-approval-live-20260628-2045\talos-workspace`
- Raw transcript path: terminal transcript in current Codex turn; not committed
- Trace path or `/last trace` summary: `trc-19268b7b-5cd6-40b3-a321-5c729a18a798`, `trc-a7601e62-18b6-401c-8ecd-637a61e3078d`, `trc-56b341ec-e7d9-404d-8fac-052651bdb19e`, `trc-76920c24-2ff6-4608-a7c7-40f0a7e4aacb`
- File diff summary: none
- Approval choices: none for read-only mode probes; later approval probes separately verified deny/yes/session behavior
- Checkpoint id: n/a
- Verification status: focused renderer regression added and passing locally

Redacted prompt sequence:

```text
/mode ask
Create ask-created.txt with the text ASK SHOULD NOT WRITE.
/last trace

/mode plan
Create plan-refuse.txt with exactly PLAN MODE MUST NOT WRITE.
/last trace

/mode agent
Using talos.write_file, overwrite README.md ...
/last trace
```

Expected behavior:

```text
`/last trace` should expose the canonical active/resolved mode for the turn,
so Ask, Plan, Agent, Auto, and legacy aliases can be audited from the same
slash-command evidence surface users are told to run after each turn.
```

Observed behavior:

```text
The installed `/last trace` output renders task contract, prompt audit, visible
tools, approvals, checkpoint, verification, and outcome, but no `mode` line.
This happened for Ask, Plan, Agent, and Auto probes. The turn trace therefore
cannot visually prove from `/last trace` alone that the read-only Ask/Plan
posture came from the selected public mode.
```

Code evidence:

- `LocalTurnTrace` already has a first-class `mode` field:
  `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java`.
- `TurnProcessor` computes `traceMode = modes.traceMode(userInput)` before
  starting local trace capture:
  `src/main/java/dev/talos/runtime/TurnProcessor.java`.
- `ReplRouter.formatCurrentTurnTrace(...)` prints `localTrace.mode()` in the
  debug current-turn helper:
  `src/main/java/dev/talos/cli/repl/ReplRouter.java`.
- `/last trace` is rendered by `ExplainLastTurnCommand.renderTrace(...)` and
  `appendLocalTrace(...)`; that renderer prints local trace id, schema,
  redaction, task contract, visible tools, prompt audit, events, checkpoint,
  verification, and outcome, but does not append `trace.mode()`:
  `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`.
- `ExplainLastTurnCommandTest` has many trace rendering assertions, but no
  assertion that local trace mode is shown:
  `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`.

## Classification

Primary taxonomy bucket:

- `TRACE_REDACTION`

Secondary buckets:

- `CURRENT_TURN_FRAME`
- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
This is not a mutation or approval bypass. The trace artifact itself appears to
carry the mode, and `/status --verbose` reports current mode. The failure is
that the user-facing audit command omits a field required by the Ask/Plan/Agent
refactor's evidence contract.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add a cosmetic line to /last trace.
```

Architectural hypothesis:

```text
The canonical mode is trace evidence, not UI chrome. Any trace-rendering path
that claims to expose the local trace should render the captured mode from
`LocalTurnTrace`, and tests should pin legacy aliases resolving to canonical
`agent` rather than old names.
```

Likely code/document areas:

- `ExplainLastTurnCommand`
- `ExplainLastTurnCommandTest`
- `ReplRouterTraceTest`

Why a one-off patch is insufficient:

```text
The Ask/Plan/Agent refactor explicitly needs mode auditability. Printing mode
only in the transient debug prompt path is insufficient when the durable user
workflow is `/last trace`.
```

## Goal

```text
`/last trace` renders the canonical local trace mode for every recorded turn,
including Ask, Plan, Agent, Auto, and legacy aliases resolved to Agent.
```

## Non-Goals

- No changes to mode routing.
- No changes to approval or permission behavior.
- No raw transcript commits.
- No change to local trace redaction mode beyond showing the already-captured
  non-sensitive canonical mode.

## Implementation Notes

```text
Append a `Mode:` or `mode:` line in the `/last trace` Local Trace block from
`LocalTurnTrace.mode()`. Keep capitalization/style consistent with the existing
trace output. Add tests around `ExplainLastTurnCommand.renderTrace(turn, trace)`
with `mode("plan")` and at least one legacy-alias trace that stores canonical
`agent`.
```

## Architecture Metadata

Capability:

- local turn trace rendering

Operation(s):

- trace display only

Owning package/class:

- `ExplainLastTurnCommand`, `LocalTurnTrace`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: installed `/last trace` smoke plus focused renderer tests
- Verification profile: no model call required
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: n/a
- Trace/debug fields: render already-captured canonical mode

Refactor scope:

- `<allowed: ExplainLastTurnCommand trace rendering and focused tests>`
- `<forbidden: mode-routing rewrite, trace schema migration, approval-policy changes>`

## Acceptance Criteria

- `/last trace` includes the canonical mode from `LocalTurnTrace.mode()`.
- Ask and Plan traces visibly prove read-only mode posture from the slash
  command output.
- `dev`, `chat`, and `unified` alias turns render canonical `agent`, not the
  legacy alias.
- No raw prompt, file content, or protected path content is newly exposed by
  trace rendering.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `ExplainLastTurnCommandTest`
- Integration/executor test: optional `ReplRouterTraceTest`
- JSON e2e scenario: n/a
- Trace assertion: `/last trace` text includes canonical mode

Manual/TalosBench rerun:

- Prompt family: installed `/mode ask`, `/mode plan`, `/mode dev`, then
  `/last trace`
- Workspace fixture: small local fixture workspace
- Expected trace: `mode: ask`, `mode: plan`, `mode: agent`
- Expected outcome: mode line present without changing other trace fields

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- No candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.

## Resolution - 2026-06-30

- `/last trace` now renders `Mode: <value>` in the Local Trace block from
  `LocalTurnTrace.mode()`, using `none recorded` only for old/blank trace
  records.
- `ExplainLastTurnCommandTest` now pins the stored-trace command path, a Plan
  read-only trace, and a legacy-alias-resolved Agent trace.
- No mode routing, approval, permission, checkpoint, or trace schema behavior
  changed.
- Focused evidence: `.\gradlew.bat test --tests
  "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon` failed
  red on the missing `Mode:` lines, then passed after the renderer change.

## Known Risks

- A clean installed-product smoke should still confirm the rendered mode line
  after the next global install/candidate build.

## Known Follow-Ups

- None.
