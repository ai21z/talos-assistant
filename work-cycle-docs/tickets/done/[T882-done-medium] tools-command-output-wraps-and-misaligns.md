# [T882-done-medium] /tools output wraps and misaligns on long descriptions

Status: done
Priority: medium

## Evidence Summary

- Source: owner manual REPL testing (2026-06-27), image of `/tools`
- Date: 2026-06-27
- Talos version / commit: 0.10.6 / 6f84f982
- Verification status: reproduced from the owner screenshot + source read

Observed: `/tools` is hard to read. Tools with short descriptions (grep, list_dir,
mkdir, read_file, ...) render in a clean name column, but tools with long
descriptions (apply_workspace_batch, copy_path, delete_path, edit_file, move_path,
run_command) overflow the line, get re-wrapped to column 0, and lose alignment with
their params line. The page looks randomly wrapped.

Root cause: `ToolsCommand` renders each tool as `name(padded 20) + badge +
FULL description` on one line. The description is not length-bounded (unlike
`HelpCommand`, which caps summaries via `listSummary`), so long descriptions push
the line past the content width and the answer renderer/terminal re-wraps it,
collapsing the leading indent. `pad(name, 20)` also only adds a single space when a
name exceeds the column (e.g. apply_workspace_batch = 21 chars), so long names break
the column too.

## Goal

`/tools` renders cleanly at any terminal width: no line overflows the content width,
descriptions are shown in full (not truncated), and each tool's name, risk badge,
description, and params are clearly associated.

## Likely code areas

- `src/main/java/dev/talos/cli/repl/slash/ToolsCommand.java` (the render loop, `badge`, `pad`)

## Non-Goals

- No change to the tool registry, descriptors, or `extractParams` logic.
- No truncation of descriptions (show them in full, wrapped).
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- Each tool renders as a short `name  badge` line, then its description word-wrapped
  at a fixed conservative width with a consistent hanging indent, then its params.
- No emitted visible line exceeds a fixed width bound (so the renderer/terminal does
  not re-wrap and mangle it), regardless of name or description length.
- Existing `/tools` content (names, badges, params, header, examples, ASCII-safety)
  is preserved.
- Regression test: the word-wrap helper produces width-bounded lines that rejoin to
  the original text; a tool with a long description renders with no over-width line.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ToolsCommandTest" --no-daemon
```

Manual: `/tools` in `talos run` reads cleanly (owner visual confirm).

## Work-Test Cycle Notes

- Inner dev loop; no version bump. Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.

## Closeout (2026-06-27)

Implemented in the inner loop on `improvement/qodana-cleanup`. `ToolsCommand` now
renders each tool as a block: a short `  name  badge` line, the full description
word-wrapped at a fixed 70-char width with a 6-space hanging indent (new `wrap`
helper), then the params at the same indent, with a blank line between tools. The
old single-line `name(padded 20) + badge + full description` layout (which
overflowed and re-wrapped on long descriptions, and broke the column on long names
like apply_workspace_batch) is gone; the now-unused `pad`/`NAME_COL` were removed.
The risk badge is the bare colored word.

Acceptance met: descriptions shown in full (not truncated), word-wrapped; no emitted
line exceeds the content width (verified by test); names/badges/params/header/
examples and ASCII-safety preserved. Tests: `ToolsCommandTest` 14/0 -- added
`wrap` width-bound + rejoin, `wrap` null/blank, and a long-description no-over-width
render check; the ASCII-safe test's contiguous-phrase assertion was made
whitespace-tolerant for the new wrapping. Focused suite BUILD SUCCESSFUL. Final
visual confirm is the owner's on a real terminal. Broad `check` deferred to the
end-of-batch candidate run.
