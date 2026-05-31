# [done] Ticket: CLI Last-Run Introspection
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- docs/architecture/30-cli-ui-output-architecture-audit.md
- work-cycle-docs/tickets/new-work.md

## Why This Ticket Exists
Users and developers need a compact way to inspect the latest recorded turn
without reading raw session JSONL files.

## Problem
`/explain-last-turn` existed but was verbose to type and had no focused views
for tools, sources, or trace detail.

## Goal
Add `/last` as a practical alias with narrow views for summary, tools, sources,
and trace.

## Scope
In scope:
- `/last`
- `/last tools`
- `/last sources`
- `/last trace`
- Existing `/explain-last-turn` compatibility.

Out of scope:
- Dedicated `/logs` command.
- Full trace event timeline.
- Log file browser.

## Proposed Work
- Extend `ExplainLastTurnCommand` aliases and argument parsing.
- Render focused views from existing `TurnRecord` data.
- Keep output trusted and renderer-owned.

## Likely Files / Areas
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`

## Test / Verification Plan
- Focused command tests.
- Full `test`.
- Full `e2eTest`.
- Installed CLI transcript with `/last`, `/last tools`, `/last sources`,
  and `/last trace`.

## Acceptance Criteria
- `/last` works as an alias.
- Tools/sources/trace focused views work.
- Unknown views show usage.
- Installed transcript has no replacement characters.
