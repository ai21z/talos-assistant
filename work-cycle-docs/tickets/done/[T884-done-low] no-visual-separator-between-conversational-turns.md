# [T884-done-low] No visual separation between consecutive conversational turns

Status: done
Priority: low

## Evidence Summary

- Source: owner manual REPL testing (2026-06-27), image of turn 1 -> turn 2
- Date: 2026-06-27
- Talos version / commit: 0.10.6 / 4f8f50a7
- Verification status: reproduced from the owner screenshot + source read

Observed: after a turn ends, the next `talos [auto] >` prompt prints almost
immediately under the previous turn's last line. Turn 2 butts up against the end of
turn 1, so the transcript reads as one undifferentiated stream. The owner asked for
"a bigger space between the turn end and the talos [auto] > new line/turn -- at
least an enter, but I would recommend a grey line so the turns are completely
separated."

Root cause: `ReplRouter.processPrompt` renders the answer, then turn stats, then the
optional compaction notice, and returns; the `RunCmd` loop then reprints the prompt
with no inter-turn chrome. The only blank lines are *inside* the answer pane
(breathing room around the response), not *between* one turn and the next prompt.

## Goal

Consecutive turns read as distinct blocks: a completed conversational turn is
followed by clear separation (a blank line and a dim rule) before the next prompt.

## Likely code areas

- `src/main/java/dev/talos/cli/repl/RenderEngine.java` (new interactive-only separator)
- `src/main/java/dev/talos/cli/repl/ReplRouter.java` (`processPrompt` turn-end seam)

## Non-Goals

- No separator after slash-command output (scoped to real prompt turns).
- No change to scripted/redirected transcript bytes (evidence chain string-matches them).
- No change to the answer pane, turn stats, route hint, or compaction notice.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Architecture Metadata (abbreviated)

- Capability: none (interactive render chrome only).
- Owning class: `RenderEngine` (render-side), invoked by `ReplRouter.processPrompt`.
- Risk/approval/protected paths: none.
- Outcome/trace: none. The rule never enters any `Result`; it is not part of any
  trace or outcome record, so outcome-truth and trace integrity are untouched.

## Acceptance Criteria

- After a conversational turn, the REPL prints a blank line, a dim full-width rule,
  and a blank line before the next prompt.
- Interactive-only: in non-interactive (piped/redirected/scripted) mode nothing is
  emitted, so those transcripts stay byte-for-byte identical.
- The rule is width-bounded and glyph-aware (`─` on Unicode-safe terminals, `-`
  otherwise) so it never overflows or renders as `?`.
- Scoped to real prompt turns, not slash-command output.
- Regression tests: the rule prints when interactive, prints nothing when
  non-interactive, and the rule body is width-bounded for both glyph modes.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.RenderEngineTest" --no-daemon
```

Manual: in `talos run`, turn 1 and turn 2 are clearly separated (owner visual confirm).

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One-line `## [Unreleased]` CHANGELOG entry added.

## Closeout (2026-06-27)

Implemented in the inner loop on `improvement/qodana-cleanup`. Added
`RenderEngine.printTurnSeparator()` -- interactive-gated, prints a blank line, a
`theme.muted(...)` rule at the resolved answer-pane width, and a trailing blank
line -- plus a package-private static `turnSeparatorLine(width, unicode)` helper
(clamped to [8, 120], `─`/`-` by glyph mode). `ReplRouter.processPrompt` calls it
once at the very end of a turn, after the compaction-notice block, so it is scoped
to real prompt turns and not slash-command output.

Acceptance met. Interactive-only (the existing `interactive` gate already keeps
scripted transcripts identical). `RenderEngineTest$TurnSeparator` 3/0 -- prints a
dim rule when interactive, prints nothing when non-interactive (byte-identical
guard), and the rule body is width-bounded/glyph-aware for both modes. Focused
suite BUILD SUCCESSFUL. Final visual confirm is the owner's on a real terminal.
Broad `check` run at end of batch.
