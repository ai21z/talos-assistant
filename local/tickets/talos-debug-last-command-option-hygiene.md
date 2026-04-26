# [done] Ticket: Debug Last Command Option Hygiene
Date: 2026-04-26
Priority: low
Status: done
Architecture references:
- `work-cycle-docs/work-test-cycle.md`
- `local/tickets/talos-cli-last-run-introspection.md`
- `local/tickets/talos-current-turn-debug-trace.md`

## Why This Ticket Exists

Manual testing relies heavily on debug commands. The installed debug run showed
that `/explain-last-turn --verbose` returns a terse usage error that points to
`/last`, which is technically correct by implementation but confusing during
manual QA.

## Problem

Prompt:

```text
/explain-last-turn --verbose
```

Observed:

```text
x [200] Usage: /last [summary|tools|sources|trace]
```

The help page lists `/explain-last-turn [opts]`, but the command accepts only
`summary`, `tools`, `sources`, and `trace`. A tester naturally tries
`--verbose` after seeing `/status --verbose`.

## Goal

Make debug introspection commands self-explanatory and hard to misuse during
manual QA.

## Scope

### In scope

- Accept `--verbose` as an alias for `trace`, or return a clearer error.
- Align `/help all`, `/help debug`, and command detail text with accepted
  options.
- Keep `/last trace` as the canonical short form.

### Out of scope

- Redesigning turn audit storage.
- Changing trace contents.

## Proposed Work

1. Update `ExplainLastTurnCommand.normalizeView()` to map:

   ```text
   --verbose -> trace
   -v -> trace
   verbose -> trace
   ```

2. Update command usage/help text.
3. Add a command unit test.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`
- `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest"
```

Installed CLI check:

```text
/debug trace
hello
/explain-last-turn --verbose
/last trace
```

## Acceptance Criteria

- `/explain-last-turn --verbose` produces a trace view or a clear corrective
  hint.
- `/help debug` names the accepted views.
- Manual QA transcripts no longer contain confusing usage failures for this
  common debug command.

## Resolution Notes

`/last`, `/explain`, and `/explain-last-turn` now accept `--verbose`, `-v`, and
`verbose` as aliases for `trace`. Command usage includes `--verbose`, and turn
selection now chooses the newest timestamp so restarted turn numbers do not
surface stale saved turns.

Installed CLI retest:

```text
/last --verbose
Last Turn
...
Trace Detail
  Contract: SMALL_TALK mutationAllowed=false verificationRequired=false
```
