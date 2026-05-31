# [done] Ticket: CLI Clear/Reset Accessibility
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- docs/architecture/30-cli-ui-output-architecture-audit.md
- work-cycle-docs/tickets/new-work.md

## Why This Ticket Exists
Users may naturally look for a reset command when they want to start a clean
conversation context.

## Problem
`/clear` existed, but `/reset` was not available as an accessible alias.

## Goal
Add a cross-platform-safe `/reset` alias for the existing conversation reset
behavior and make help mention it.

## Scope
In scope:
- `/reset` alias.
- Help wording for clear/reset.

Out of scope:
- Full terminal screen clearing.
- Ctrl+L terminal binding changes.
- Persistent session file deletion.

## Proposed Work
- Add `reset` to `ClearCommand` aliases.
- Update default help wording.

## Likely Files / Areas
- `src/main/java/dev/talos/cli/repl/slash/ClearCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`
- `src/test/java/dev/talos/cli/repl/slash/ClearCommandTest.java`

## Test / Verification Plan
- Focused clear/help tests.
- Full `test`.
- Full `e2eTest`.
- Installed CLI transcript with `/help`, `/reset`, and `/exit`.

## Acceptance Criteria
- `/reset` invokes the existing conversation reset behavior.
- Help mentions the alias.
- Installed transcript has no replacement characters.
