# [open] Ticket: Dev Mode Natural File Listing Misroutes
Date: 2026-04-26
Priority: medium
Status: open
Architecture references:
- `local/tickets/new-work.md`
- `work-cycle-docs/work-test-cycle.md`
- `local/prompts/talos-manual-qa-suite.md`

## Why This Ticket Exists

Manual mode/tool QA must verify that every visible mode behaves naturally. The
installed retest showed `dev` mode failing a simple natural file-list request.

## Problem

Prompt sequence:

```text
/mode dev
list the files here
```

Observed:

```text
i Not found: the
```

The prompt is a normal user request, but `dev` mode appears to route part of it
as a lookup/path command and reports the token `the` as missing.

## Goal

In `dev` mode, natural requests like "list the files here" should either use
the workspace listing tool or clearly guide users to the canonical command
without treating arbitrary words as paths.

## Scope

### In scope

- Inspect `dev` mode routing for natural language file/list requests.
- Add a deterministic command/mode regression test.
- Decide whether `dev` should remain a separate user-visible mode or be folded
  into fewer modes (`auto`, `fast`, `thinking`) after architectural review.

### Out of scope

- Shell execution.
- Background autonomy.
- Large mode redesign without a separate mode-simplification ticket.

## Proposed Work

1. Reproduce with a small workspace fixture.
2. Identify whether the failure lives in `ModeController`, dev-mode command
   parsing, or slash command fallback.
3. Add a test for:

   ```text
   /mode dev
   list the files here
   ```

4. Make the response list files or provide a clear `/files` hint.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/`
- `src/main/java/dev/talos/cli/repl/`
- `src/test/java/dev/talos/cli/modes/`
- `src/test/java/dev/talos/cli/repl/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "*Mode*"
./gradlew.bat e2eTest
```

Installed CLI check:

```text
/debug trace
/mode dev
list the files here
/last trace
```

## Acceptance Criteria

- Dev mode no longer returns `Not found: the` for natural file-list prompts.
- The response either lists workspace files or gives a precise command hint.
- Manual QA suite includes a dev-mode natural file-list prompt.
