# [T901-done-medium] No live "working" indicator during the long model-generation / tool-loop wait

Status: done
Priority: medium

## Evidence Summary

- Source: owner manual-test session, 2026-06-28 (image 1 + transcript; turns took 144s and 271s)
- Talos version / commit: 0.10.6 / afcaf93c (branch improvement/qodana-cleanup)
- Verification status: root cause code-verified; fixed + focused tests green; pending owner interactive manual confirmation on the installed build.

Observed (live): after a prompt, during the long wait the screen showed the route banner and the streamed tool lines ("read style.css", "read index.html", "read script.js"), then a bare cursor with no animation while the model generated for 100-270s. No spinner, no elapsed timer, no "working" graphic.

## Root Cause

The spinner subsystem exists and is enabled by default ("Answering..." with an elapsed counter, [RenderEngine.startSpinner](src/main/java/dev/talos/cli/repl/RenderEngine.java:235); config default `show_status_during_answer: true`). It is started once before the turn ([ReplRouter.processPrompt](src/main/java/dev/talos/cli/repl/ReplRouter.java:163)) and stopped on the model's FIRST visible output ([AssistantTurnExecutor](src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java) onStreamComplete -> `render::stopSpinner`, wired at [TalosBootstrap](src/main/java/dev/talos/cli/repl/TalosBootstrap.java:360); also the first-token sinks at [RenderEngine.java:315,355,361](src/main/java/dev/talos/cli/repl/RenderEngine.java:315)). It was NEVER restarted for subsequent model-generation rounds. The tool loop owns only a `ToolProgressSink` ([TalosBootstrap.java:277](src/main/java/dev/talos/cli/repl/TalosBootstrap.java:277)) and had no handle to re-arm the spinner. So after the first tool line, every later generation round (the slow part) ran with no indicator.

## Fix

Re-arm the spinner at the natural seam: after each tool-progress line, the very next thing is a model-generation wait.
- [RenderEngine.printToolProgressResumingSpinner](src/main/java/dev/talos/cli/repl/RenderEngine.java): prints the tool line then `startSpinner()` to cover the next generation round.
- Wired as the tool-loop progress sink at [TalosBootstrap.java:277](src/main/java/dev/talos/cli/repl/TalosBootstrap.java:277) (`render::printToolProgress` -> `render::printToolProgressResumingSpinner`).
- [RenderEngine.printToolProgress](src/main/java/dev/talos/cli/repl/RenderEngine.java:463) now `stopSpinner()` before printing its line, so a legacy `\r` frame can never collide with a tool line (single-writer discipline T774/T779).

The spinner is re-armed per round and stopped again on the next visible output (first answer token, next tool line, or the approval gate, which already stops it via its own `spinnerStopper` before prompting). This covers the dominant dead-air case the owner saw (the long generation after the read loop, e.g. the 144s wait after the three reads in turn 10).

## Trust / Invariants respected

- Single-writer / cursor discipline (T774/T779): every tool line clears the spinner first; the JLine status-row path and the approval gate's own spinner-stop are unchanged.
- Non-interactive output stays byte-identical: `printToolProgress` and `startSpinner` both early-return when `!interactive`, so the resuming sink is a no-op in scripted/redirected runs (the evidence transcripts do not change).
- No approval / permission / checkpoint / outcome-truth change; this is presentation chrome only.

## Scope / Non-Goals

- Does not add a spinner during the single post-approval final-generation round (the approval gate stops it and there is no later tool line to re-arm). The dominant multi-tool / long-generation case is covered; a fuller "indicator owned by the turn executor across every dispatch round" is a larger change deferred unless needed.
- No switch of the default indicator path (legacy `\r` vs JLine status row) or terminal-capability changes.

## Tests / Evidence

[RenderEngineTest](src/test/java/dev/talos/cli/repl/RenderEngineTest.java) (SpinnerResumeDuringToolLoop): a running spinner is cleared before a tool line prints; the resuming sink re-arms the spinner after a tool line and `shutdown()` stops it; non-interactive prints no tool line and runs no spinner. New package-private `RenderEngine.spinnerRunning()` test accessor (legacy-path scoped). Owner interactive manual confirmation pending on the refreshed install.

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (code + test + ticket).
