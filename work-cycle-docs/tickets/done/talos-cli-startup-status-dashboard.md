# [done] Ticket: CLI Startup Status Dashboard
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- work-cycle-docs/tickets/new-work.md
- docs/new-architecture/30-cli-ui-output-architecture-audit.md
- local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md

## Why This Ticket Exists
Normal CLI startup and status output should be calm, compact, and useful.
The previous full banner and default `/status` output mixed normal user
context with detailed diagnostics.

## Problem
Startup and status output were visually noisy and did not clearly separate
the normal user path from developer diagnostics.

## Goal
Add a compact dashboard for startup and default status output while preserving
the detailed diagnostic view behind `--verbose`.

## Scope
In scope:
- Shared compact startup/status dashboard.
- Default `/status` and top-level `talos status` dashboard.
- `--verbose` diagnostic status remains available.
- Remove legacy large startup banner path.
- Suppress default Talos INFO/DEBUG console logs.

Out of scope:
- Layered help redesign.
- Full debug-level architecture.
- Full role/result rendering cleanup.

## Proposed Work
- Add a shared `CliStatusDashboard`.
- Route startup and default status commands through it.
- Keep detailed status output behind `--verbose`.
- Keep normal output ASCII-safe in dumb/non-interactive terminals.

## Likely Files / Areas
- `src/main/java/dev/talos/cli/ui/`
- `src/main/java/dev/talos/cli/repl/slash/StatusCommand.java`
- `src/main/java/dev/talos/cli/launcher/TopLevelStatusCmd.java`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/resources/logback.xml`

## Test / Verification Plan
- Focused CLI UI and slash command tests.
- Full `test`.
- Full `e2eTest`.
- Installed CLI run in `local/playground/horror-synth-site`.

## Acceptance Criteria
- Default startup shows app/version, workspace, mode, model, index, policy,
  debug state, and next action.
- Default `/status` is compact.
- `/status --verbose` keeps detailed diagnostics.
- Installed CLI transcript has no replacement characters from UI separators.
- Normal startup does not show Talos INFO/DEBUG log lines.
