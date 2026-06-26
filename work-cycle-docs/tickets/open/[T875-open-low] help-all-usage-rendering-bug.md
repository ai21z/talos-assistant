# [T875-open-low] /help all usage rendering: name-vs-usage mismatch and option collapse

Status: open
Priority: low

## Evidence Summary

- Source: /help-surface multi-agent audit (run woar8xy4f)
- Date: 2026-06-26
- Talos version / commit: 0.10.6 / bb8b659a
- Verification status: source-verified, adversarially confirmed

## Findings

1. (low, latent corruption) `HelpCommand.compactUsage` builds
   `String cmd = "/" + spec.name();` (`HelpCommand.java:261`) then
   `rest = usage.substring(cmd.length())` (line 262), assuming the usage string
   starts with `"/" + name`. `ExplainLastTurnCommand` has name `explain-last-turn`
   but usage `/last [summary|tools|sources|trace|--verbose]`
   (`ExplainLastTurnCommand.java:65-70`), so the substring cuts mid-token to
   `ls|sources|trace|--verbose]`. It only avoids rendering that literal garbage
   because the mangled string exceeds USAGE_COL and the length fallback at line 269
   rescues it to `/explain-last-turn [opts]` -- a name no user types (the
   documented form is `/last`). A few characters shorter and the corruption would
   render directly. It is also the only `/help all` row inconsistent with the rest
   of the help surface, which calls it `/last`.
2. (low) `/help all` collapses enumerated subcommand options to `[opts]` for
   usages whose options are not `--`-prefixed and exceed USAGE_COL=24:
   `/debug`, `/session`, `/last`, `/checkpoint`, `/privacy` all render `[opts]`,
   hiding the valid values (which only reappear via `/help <cmd>`).
   `HelpCommand.java:257-269`.

## Goal

`/help all` renders each command under the name the user actually types and never
silently corrupts or over-collapses a usage string.

## Likely code areas

- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java` (`compactUsage`, lines 257-270; footer hint 180-189)

## Non-Goals

- No restructuring of the help layout; minimal fix to the usage-token derivation.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- `compactUsage` derives the command token from the usage string's own leading token (not `spec.name()`), so `/last` renders as `/last ...`.
- No spec can produce a mid-token-sliced usage string.
- Optionally the footer states that `/help <cmd>` shows the full option list.
- Regression test pins the `/help all` line for `explain-last-turn` (-> `/last ...`) and one enumerated-option command.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "*SimpleCommandsTest" --tests "dev.talos.cli.repl.slash.*" --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop; no version bump. Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.
