# [done] Ticket: CLI Layered Help
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- docs/new-architecture/30-cli-ui-output-architecture-audit.md
- work-cycle-docs/tickets/new-work.md

## Why This Ticket Exists
Default help should guide normal users without dumping every command. Detailed
developer and operator help should remain available on demand.

## Problem
`/help` was grouped, but still functioned like a full command inventory. That
made normal-mode output feel heavier than necessary.

## Goal
Make `/help` short by default and add topic pages for full inventory, debug,
security, and RAG/workspace context.

## Scope
In scope:
- `/help`
- `/help all`
- `/help debug`
- `/help security`
- `/help rag`
- `/help <cmd>`

Out of scope:
- Debug-level runtime architecture.
- Approval UI redesign.
- New command groups.

## Proposed Work
- Keep the existing registry-backed help model.
- Split default, full inventory, topic, and command-detail render paths.
- Keep command list summaries short enough for dumb/non-interactive output.

## Likely Files / Areas
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`
- `src/test/java/dev/talos/cli/repl/slash/SimpleCommandsTest.java`

## Test / Verification Plan
- Focused slash command tests.
- Full `test`.
- Full `e2eTest`.
- Installed CLI transcript with `/help`, `/help all`, `/help debug`,
  `/help security`, `/help rag`, and `/help <cmd>`.

## Acceptance Criteria
- Default `/help` is short and practical.
- Full command inventory remains available through `/help all`.
- Focused debug/security/RAG help pages exist.
- Command detail help still works.
- Installed transcript has no replacement characters.
