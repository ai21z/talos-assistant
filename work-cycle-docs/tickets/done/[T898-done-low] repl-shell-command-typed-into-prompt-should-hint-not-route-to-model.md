# [T898-done-low] A shell invocation of the talos binary typed into the REPL prompt should hint, not route to the model

Status: done
Priority: low

## Evidence Summary

- Source: owner manual-test session, 2026-06-28 (model llama_cpp/qwen3.6-35b-a3b-q4km, global build 2026-06-28T14:33:41Z)
- Talos version / commit: 0.10.6 / 01da420e (branch improvement/qodana-cleanup)
- Verification status: root cause code-verified; reproduced by the live transcript; fixed + focused tests green.

Observed (live): the owner typed the shell command `talos setup models --profile Qwen3.6-14B-A3B-VibeForged-v2-Q6_K --write --force` INTO the Talos REPL prompt. Talos routed it to an agent turn ("route agent") and the 35B model produced a hallucinated answer ("I am Talos ... The command you ran is from a different project entirely"). The user got no signal that this is a shell command meant for their terminal.

## Root Cause

Two independent layers. This ticket fixes the harness one.

1. ROUTING / NO GUARD (harness, fixed here): [LineClassifier.classify](src/main/java/dev/talos/cli/repl/LineClassifier.java:14) treats only a leading `/` as a COMMAND; every other non-empty line is a PROMPT. [ReplRouter.tryHandlePrompt](src/main/java/dev/talos/cli/repl/ReplRouter.java:129) then forwards it to the model via the auto router ([ModeController.routeAuto](src/main/java/dev/talos/cli/modes/ModeController.java)). Nothing detected that the line was an attempt to run the talos binary, and nothing hinted "run it in your terminal." The line was spent on a model turn.
2. THE HALLUCINATION ITSELF (model-competence, out of scope): once routed, the fabricated identity answer was produced by the off-doctrine 35B model. No Talos code produces or sanctions that text. The file-mutation anti-overclaim surface is scoped to mutation turns by design, so a conversational hallucination on a no-tool turn is not what it guards.

## Fix

New pure detector [ShellCommandHint](src/main/java/dev/talos/cli/repl/ShellCommandHint.java): `detect(rawLine)` returns a hint when the trimmed line's first token is the talos binary (`talos`, `talos.bat`, `talos.exe`, `talos.cmd`, case-insensitive) AND the second token is either a flag (`-...`) or a real top-level subcommand (`setup, run, net, status, version, diagnose, doctor, rag-index, rag-ask, prompt-render`, derived from [RootCmd](src/main/java/dev/talos/cli/launcher/RootCmd.java:11)). [ReplRouter.tryHandlePrompt](src/main/java/dev/talos/cli/repl/ReplRouter.java:129) calls it before routing; on a hit it renders an actionable nudge (run it in your terminal; in here use /models, /set model, /help) and returns handled, spending no model turn.

Kept narrow to avoid false positives: requires the binary name as the FIRST token plus a subcommand/flag as the second, so a normal question that merely mentions "talos" ("what does talos do with my files?") is untouched. Trade-off accepted: a line like "talos run the tests" can match the verb-like subcommands `run`/`status`; the nudge is non-destructive (informational only, the user simply retypes), so this is acceptable for a low-priority UX guard.

## Non-Goals

- No change to model behavior or the hallucination itself (model-competence; mitigated by moving off the 35B per the model-switch guidance, T899).
- No new LineClassifier category (its contract stays intact and reused across the REPL).
- No approval / permission / checkpoint / outcome-truth change. Pure presentation nudge.

## Tests / Evidence

[ShellCommandHintTest](src/test/java/dev/talos/cli/repl/ShellCommandHintTest.java): the live bug string and `talos -v` / `talos --version` / `talos doctor` / `talos rag-index` / `talos.bat setup` / `talos.exe --version` / `TALOS SETUP` / leading-whitespace all detect; normal prompts ("what does talos do with my files?", "talos can you read this file", bare "talos", "ls src", "/models", null, blank) do not. Focused run with LineClassifierTest green.

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (code + test + ticket).
