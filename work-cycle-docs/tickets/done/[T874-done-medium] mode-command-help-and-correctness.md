# [T874-open-medium] Mode command help and correctness cluster

Status: open
Priority: medium

## Evidence Summary

- Source: owner manual REPL testing + /help-surface multi-agent audit (run woar8xy4f)
- Date: 2026-06-26
- Talos version / commit: 0.10.6 / bb8b659a
- Model/backend: managed llama.cpp; qwen3.6-35b-a3b-q4km (live)
- Workspace fixture: not applicable (CLI help/command surface)
- Verification status: source-verified, adversarially confirmed; no runtime diff yet

## Findings

All five are in the `/mode` surface and were each verified against source.

1. (medium) `/mode web` succeeds into a dead stub. The advertised list says
   "web (reserved)" but `WebMode` IS registered (`ModeController.java:221`
   `.add(new WebMode())`) and `setActive` accepts any registered key
   (`ModeController.java:92`), so `/mode web` returns a green "Mode: web" and then
   `ModeController.route()` dispatches every later prompt to `WebMode.handle()`,
   which only prints a reserved-stub notice (`WebMode.java:17-25`). The user is
   trapped until `/mode auto`. The "(reserved)" label and the success contradict.
2. (medium) The advertised mode list is a hardcoded string literal duplicated in
   `ModeCommand.java:16` (spec summary) and `ModeCommand.java:27` (error), not
   derived from the registry, so it has already drifted: it omits the real
   registered mode `unified` and mislabels `web`.
3. (low) Default `/help` overrides `/mode`'s informative spec summary with the
   terse "switch operating mode" (`HelpCommand.java:110`), dropping every mode
   name; the useful summary at `ModeCommand.java:16` is hidden on the default page.
4. (low) Bare `/mode` echoes only the current mode and never lists the options
   (`ModeCommand.java:22-24`); the option set appears only on a wrong guess or via
   `/help mode`.
5. (low) `/mode` advertises the alias `chat` but never the real registered name
   `unified` (`UnifiedAssistantMode.java:59`); `/mode unified` works but is
   undiscoverable.

## Goal

`/mode` help and behavior agree and stay in sync with the registry: the advertised
set is derived from `ModeController` (no hardcoded literal), reserved stubs are
rejected or clearly marked on success, bare `/mode` lists the available modes, and
canonical names (not just aliases) are discoverable.

## Likely code areas

- `src/main/java/dev/talos/cli/modes/ModeController.java` (expose settable/annotated mode names)
- `src/main/java/dev/talos/cli/repl/slash/ModeCommand.java` (derive list; bare-`/mode` listing; reserved-mode handling)
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java` (the line-110 override)
- `src/main/java/dev/talos/cli/modes/WebMode.java` (reserved-mode contract)

## Non-Goals

- No new modes or mode behavior; help/correctness only.
- Do not implement WebMode's web capability here (separate future scope).
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- The advertised mode list is generated from the controller's registered set; adding/removing a mode in `defaultController()` updates the help with no literal edits.
- `/mode web` (a reserved stub) is rejected with a clear message, or its success explicitly states it is a no-op reserved mode plus how to exit.
- Bare `/mode` lists the available modes.
- `unified` is discoverable and the alias/primary naming is consistent.
- Regression test pins: advertised set == settable set; `/mode web` behavior; bare-`/mode` lists modes.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.*" --tests "*SimpleCommandsTest" --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop; not a candidate closeout. No version bump.
- Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.
- `SimpleCommandsTest` currently asserts the misleading hardcoded mode-list string; that assertion must move to the registry-derived form.
