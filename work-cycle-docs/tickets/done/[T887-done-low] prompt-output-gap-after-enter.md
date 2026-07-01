# [T887-done-low] Prompt output starts immediately after submitted prompt

Status: done
Priority: low

## Evidence Summary

- Source: owner manual REPL testing screenshot (2026-06-27)
- Date: 2026-06-27
- Talos version / commit: 0.10.6 / 67c0cfe4
- Verification status: reproduced from screenshot + source read

Observed: after submitting a prompt such as `talos [auto] > hey`, Talos starts the
route hint on the very next line. Even with the T884 turn separator after the
previous turn, the current prompt line still reads cramped against the route/answer
block.

Root cause: `RunCmd` calls `input.readLine(prompt)` and immediately dispatches the
non-empty line to slash-command or prompt handling. JLine owns the prompt echo, but
the REPL loop had no post-submit blank line before route, slash-command, or rate
limit output.

## Goal

In real interactive terminals, pressing Enter after a non-empty `talos [mode] >`
input leaves one blank line before Talos output starts.

## Likely code areas

- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/test/java/dev/talos/cli/launcher/RunCmdTerminalModeTest.java`

## Non-Goals

- No change to answer panes, turn stats, route hint wording, or the T884
  inter-turn separator.
- No change to scripted/redirected transcript bytes.
- No change to slash-command semantics, approval, permissions, checkpoints,
  traces, prompt-debug, or verification.

## Acceptance Criteria

- Interactive non-empty prompt input prints one blank line before any Talos output.
- Empty prompt input does not add stray vertical space.
- Scripted/redirected mode remains byte-stable and does not add the prompt gap.
- Regression test covers the prompt-gap decision for interactive, scripted, empty,
  and null input.

## Tests / Evidence

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --no-daemon
```

## Closeout (2026-06-27)

Implemented in the inner loop on `improvement/qodana-cleanup`. Added a
package-private `RunCmd.shouldPrintPromptGap(interactive, line)` helper and call it
after a submitted line is sanitized and confirmed non-empty. The gap is gated to
the same `useSystemTerminal` path that owns real JLine terminal rendering, so
scripted/redirected transcripts stay unchanged.

Focused test passed after the expected red compile failure proved the new assertion
was active.
