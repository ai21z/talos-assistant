# [done] Ticket: Explain Last Turn CLI Visibility
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-source-of-truth.md`
- `docs/architecture/talos-harness-plan.md`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/work-test-cycle-step-by-step.md`

## Why This Ticket Exists

The execution-discipline roadmap says users should be able to inspect how Talos
reached a result without reading debug logs. Talos already records structured
per-turn audit data in the JSONL session log, but the CLI does not expose a
simple "what happened last turn" view.

## Problem

Talos can now enforce and summarize many discipline concepts internally:

- task contracts
- phase policy
- approval gates
- tool outcomes
- failure stops
- verification/truth annotations

But a normal user still has to infer most of that from streaming logs, tool
summaries, or local session files. That makes the architecture less teachable
and less reviewable.

## Goal

Add a narrow `/explain-last-turn` slash command that renders the latest
structured turn record from the current workspace session.

## Scope

### In scope

- Add a CLI slash command that reads the latest `TurnRecord` for the current
  workspace from the existing `SessionStore`.
- Show turn number, status, duration, approvals, retrieval trace summary, tool
  calls, and a compact inferred outcome.
- Register the command in `TalosBootstrap`.
- Add focused unit tests for rendering and command behavior.
- Run installed Talos verification and confirm the command works after a manual
  turn.

### Out of scope

- Persisting the full `TaskOutcome` model in the session log.
- Building a full phase timeline UI.
- Adding new background telemetry.
- Adding shell/browser/MCP/cloud tools.
- Changing the approval or tool-execution policy.

## Proposed Work

- Create `dev.talos.cli.repl.slash.ExplainLastTurnCommand`.
- Reuse the existing durable source:
  - `SessionStore.loadTurns(sessionId)`
  - `TurnRecord`
  - `TurnRecord.ToolCallSummary`
- Keep the command read-only and deterministic.
- Use existing slash-command conventions and `Result.TrustedInfo`.
- Infer a conservative outcome label from persisted facts:
  - approval denied -> `BLOCKED_BY_APPROVAL`
  - failed mutating tool with no successful mutation -> `FAILED_OR_BLOCKED`
  - successful mutating tool -> `MUTATION_APPLIED`
  - only read/search/retrieve tools -> `INSPECTION_RECORDED`
  - no tool calls with ok status -> `NO_TOOL_RESPONSE`
  - error/aborted/info statuses -> matching status label

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`

## Test / Verification Plan

- Focused unit test for:
  - no turns available
  - read-only turn with tool calls
  - approval-denied turn
  - mutation-applied turn
- Full local checks:
  - `./gradlew.bat --no-daemon test`
  - `./gradlew.bat --no-daemon e2eTest`
  - `./gradlew.bat --no-daemon check`
- Installed Talos verification:
  - uninstall
  - build install distribution
  - install
  - run one horror-synth inspection prompt
  - run `/explain-last-turn`
  - capture and review `local/manual-testing/test-output`

## Acceptance Criteria

- `/explain-last-turn` is listed in slash command help/completion through normal
  command registration.
- After a completed prompt, `/explain-last-turn` renders the latest turn's
  structured audit facts without reading debug logs.
- The command never mutates workspace files.
- Existing tests and e2e tests remain green.
- Installed Talos manual verification proves the command works in the standard
  horror-synth workspace.

## Completion Notes

- Added `/explain-last-turn` with alias `/explain`.
- The command reads the latest `TurnRecord` from the current workspace session
  JSONL log and renders turn number, status, inferred outcome, duration,
  approvals, tool calls, and assistant/user previews.
- Installed Talos verification in `local/playground/horror-synth-site` confirmed
  the command renders `Outcome: INSPECTION_RECORDED` after the standard
  read-only selector-inspection prompt.
- The playground files remained unchanged.
