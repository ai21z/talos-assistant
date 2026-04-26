# [done] Ticket: CLI Role and Result Rendering Cleanup
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- docs/new-architecture/30-cli-ui-output-architecture-audit.md
- work-cycle-docs/tickets/new-work.md

## Why This Ticket Exists
Normal CLI output should make command info, assistant answers, sources, errors,
and control flow visually distinct without trusting model-written styling.

## Problem
`Result.Info` output looked like plain text, RAG source suffixes could be
blended into assistant answer blocks, and the internal quit token could leak
through the renderer.

## Goal
Add a narrow renderer cleanup that improves result separation while preserving
the existing line-based interface.

## Scope
In scope:
- Prefix informational results.
- Render `[Sources]` suffixes as a separate `Sources` section.
- Suppress the internal quit control token.

Out of scope:
- Full structured UI event model.
- Debug level architecture.
- Approval prompt redesign.

## Proposed Work
- Keep `Result` variants stable.
- Normalize source suffix rendering in `RenderEngine`.
- Keep sanitization and redaction before rendering.
- Treat quit as router control flow, not terminal content.

## Likely Files / Areas
- `src/main/java/dev/talos/cli/repl/RenderEngine.java`
- `src/main/java/dev/talos/cli/repl/ReplRouter.java`
- `src/test/java/dev/talos/cli/repl/RenderEngineTest.java`
- `src/test/java/dev/talos/cli/repl/TalosBootstrapTest.java`

## Test / Verification Plan
- Focused render/router tests.
- Full `test`.
- Full `e2eTest`.
- Installed CLI transcript with `/clear`, `/help`, `/status`, and `/exit`.

## Acceptance Criteria
- Info output has a distinct prefix.
- Source suffixes are not blended into assistant answer bodies.
- The internal quit token is not shown.
- Installed transcript has no replacement characters.
