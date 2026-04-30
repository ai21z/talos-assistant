# [T63-open-low] Debug Command Level Alias Ergonomics

Status: open
Priority: low
Date: 2026-04-30

## Evidence Summary

- Source: installed Talos 0.9.8 smoke run
- Installed version: `Talos 0.9.8 - build 2026-04-30T08:33:26.239273200Z`
- Transcript reference:
  `local/manual-testing/talosbench/20260430-221050-debug-prompt/protected-read-denial-debug-prompt-one-denial.txt`

Observed behavior:

- `/debug prompt` works and enables Prompt Audit output.
- `/debug prompt on` returns usage error `[201] Usage: /debug off|brief|rag|tools|prompt|trace`.
- The user naturally requested `/debug prompt on` during smoke testing, so the
  current syntax is slightly surprising even though it is documented by
  `/help debug`.

## Classification

Primary taxonomy bucket: `CLI_UX`

Secondary buckets:

- `TRACE_REDACTION`
- `EVALUATION_HARNESS`

Blocker level: not a blocker

Why this level:

The existing command works and the help text is technically correct. This is a
small usability and manual-evaluation friction issue, not a runtime safety or
truthfulness failure.

## Goal

Make debug level toggling tolerant of the harmless `on` suffix users expect
while preserving the existing exact debug-level commands.

## Non-Goals

- No new debug levels.
- No change to trace redaction defaults.
- No change to `/last trace`, `/prompt`, or trace capture behavior.
- No broad natural-language command parser.

## Implementation Notes

Likely code/document areas:

- `src/main/java/dev/talos/cli/repl/slash/DebugCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`
- `src/test/java/dev/talos/cli/repl/slash/` or nearest existing slash command
  tests
- `tools/manual-eval/README.md`

Suggested behavior:

- `/debug prompt on` behaves like `/debug prompt`.
- `/debug trace on` behaves like `/debug trace`.
- `/debug rag on`, `/debug tools on`, and `/debug brief on` behave like their
  existing level commands.
- `/debug prompt off` behaves like `/debug off`.
- `/debug on` remains invalid unless a later ticket defines a default level.

## Acceptance Criteria

- Existing commands `/debug off`, `/debug brief`, `/debug rag`, `/debug tools`,
  `/debug prompt`, and `/debug trace` continue to work.
- Optional `on` suffix is accepted for every non-off debug level.
- Optional `off` suffix after a non-off level disables debug output.
- Invalid forms still return clear usage.
- `/help debug` mentions both canonical syntax and the optional `on` suffix.

## Tests / Evidence

Required deterministic regression:

- Slash command unit test: `/debug prompt on` sets prompt debug.
- Slash command unit test: `/debug trace on` sets trace debug.
- Slash command unit test: `/debug prompt off` sets debug off.
- Slash command unit test: `/debug on` remains invalid.

Manual/TalosBench rerun:

- Run a one-prompt protected-read denial smoke with `/debug prompt on` and
  `/last trace`; expected Prompt Audit appears and final trace remains redacted.

Commands:

```powershell
./gradlew.bat test --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

## Known Risks

- Over-accepting debug syntax can make command mistakes harder to catch. Keep
  the compatibility surface narrow and explicit.
