# [done] Ticket: CLI Debug and Trace Layering
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- docs/architecture/30-cli-ui-output-architecture-audit.md
- work-cycle-docs/tickets/new-work.md

## Why This Ticket Exists
The CLI needs a clearer debug surface than a binary on/off toggle, while
normal mode must remain quiet.

## Problem
`/debug` only represented a boolean. That made it impossible for the UI to
distinguish brief debug hints from RAG, tool, or full trace diagnostic intent.

## Goal
Add a transitional debug-level model that preserves `/debug on|off` while
accepting explicit levels: `off`, `brief`, `rag`, `tools`, and `trace`.

## Scope
In scope:
- Debug level enum.
- Backward-compatible session/runtime defaults.
- `/debug` command level parsing.
- Startup/status dashboard display of the current debug level.

Out of scope:
- Full trace event rendering.
- Log file browser.
- RAG/tool trace replay.

## Proposed Work
- Add `DebugLevel`.
- Extend session/runtime surfaces with default level methods.
- Store real debug level in `RunCmd`.
- Keep boolean compatibility through `isDebug()` and `setDebug(boolean)`.

## Likely Files / Areas
- `src/main/java/dev/talos/cli/repl/DebugLevel.java`
- `src/main/java/dev/talos/cli/repl/SessionState.java`
- `src/main/java/dev/talos/cli/repl/slash/DebugCommand.java`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/ui/TalosBanner.java`
- `src/main/java/dev/talos/cli/repl/slash/StatusCommand.java`

## Test / Verification Plan
- Focused debug command and dashboard tests.
- Full `test`.
- Full `e2eTest`.
- Installed CLI transcript with `/debug`, `/debug rag`, `/debug tools`,
  `/debug trace`, `/debug off`, and `/status`.

## Acceptance Criteria
- Legacy `/debug on|off` remains compatible.
- `/debug rag`, `/debug tools`, and `/debug trace` are accepted.
- `/status` shows the current debug level.
- Installed transcript has no replacement characters.
