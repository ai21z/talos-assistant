# T865 Deterministic Terminal Tests For Portable Green Check

Status: done
Priority: high
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Opened from: 2026-06-23 candidate-readiness hygiene

## Problem

Two tests fail or pass depending on the execution ENVIRONMENT, not the code, so a
green full `check` is not portable evidence. The same commit (`723d4cd2`) was
observed GREEN in one clean worktree (GPT) and RED in another clean worktree on
the same host (Opus) -- proving the variable is the test JVM's console/terminal
context, not tree cleanliness or any ticket. This blocks trusting "check is
green" as candidate-cut evidence.

The two tests:

- `dev.talos.cli.launcher.RootCmdTest.shortHelpOptionShowsUsage` -- asserts the
  `-h` output `contains("Usage: talos")`. picocli auto-enables ANSI when the test
  JVM has a console attached, and styles the command name, so the synopsis
  becomes `Usage: \e[1mtalos\e[21m ...` and the plain substring no longer matches.
- `dev.talos.cli.ui.StatusRowPresenterTest.dumbTerminalIsNotSupportedAndStartIsANoOp`
  -- builds a JLine terminal with `system(false).dumb(true).streams(...)` and
  expects `StatusRowPresenter.supports()` to be `false`. With explicit streams +
  `system(false)`, JLine builds an external terminal whose TYPE defaults to the
  ambient `TERM`; on an xterm-like host that type exposes scroll-region
  capabilities, so `supports()` flips to `true`.

## Root Cause

Both tests read ambient terminal/console state instead of pinning it:
picocli ANSI auto-detection, and JLine external-terminal type defaulting to
`TERM`.

## Scope

Test-only determinism fixes. No production behavior change.

- `RootCmdTest`: pin `picocli` ANSI OFF via
  `setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF))` so help is asserted as
  plain text.
- `StatusRowPresenterTest`: pin the test terminal's type to `Terminal.TYPE_DUMB`
  so capability lookup is deterministic regardless of ambient `TERM`.

## Non-Goals

- Do not weaken either assertion (still assert "Usage: talos" present and a dumb
  terminal is unsupported).
- Do not change `RootCmd` help rendering or `StatusRowPresenter.supports()`
  production logic.
- Do not touch `site/` or any unrelated dirty work (T860/T861).

## Acceptance Criteria

- Both tests pass deterministically in this host's interactive/Bash-tool
  environment (where they previously failed).
- A full `check` on a clean worktree at the fix commit is GREEN on this host
  (zero failures), making the green check portable.

## Verification (done)

Fix commit `7b8876bd` (test-only: 2 files). Verified by Opus:

- Reproduced both failures focused in this host's Bash-tool environment
  (`RootCmdTest:42` expected true was false; `StatusRowPresenterTest:40` expected
  false was true), applied the fixes, reran focused -> GREEN.
- Clean detached worktree at `7b8876bd`, fresh full `check` in the SAME
  environment that was previously RED -> `BUILD SUCCESSFUL`, zero failing classes
  (JUnit XML). The green check is now portable: the candidate-cut blocker the
  earlier "2 environmental failures" represented is cleared.
- No `src/main` change; neither assertion weakened.

## Architecture Metadata

- Capability ownership: CLI test determinism (terminal/ANSI context).
- Operation type: test hardening.
- Risk: low; test-only, no `src/main` change.
- Evidence obligation: focused red-then-green + a clean-worktree full check.
- Allowed refactor scope: the two test files only.
