# [T874-done-medium] Mode command help and correctness cluster

Status: done
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

## Closeout (2026-06-27)

Implemented in the inner loop on `improvement/qodana-cleanup`. Changes:

- `Mode.available()` (default `true`); `WebMode.available()` returns `false`.
- `ModeController.setActive` rejects unavailable/reserved modes; added
  `availableModeNames()` (auto + registered available modes, aliases excluded) and
  `reservedModeNames()`, both derived from the live registry.
- `ModeCommand` derives its spec summary, error messages, and listing from the
  controller: bare `/mode` lists the available modes; `/mode web` returns
  "Mode 'web' is reserved and not yet available"; unknown names list the set.
- `HelpCommand` default page passes `null` for the `/mode` override so it shows the
  dynamic registry-derived summary instead of the terse hardcoded text.

Acceptance met: advertised set is registry-generated (no literal, cannot drift);
`/mode web` cannot trap (rejected, stays `auto`); bare `/mode` lists modes;
`unified` is advertised while the `chat` alias still functions but is not
advertised. Tests (focused run BUILD SUCCESSFUL): `ModeControllerTest` 45/0 (added
reserved-web rejection, `availableModeNames`, `reservedModeNames`),
`SimpleCommandsTest$Mode` 13/0 (added bare-lists, web-rejected, summary),
`SimpleCommandsTest$Help` 12/0 (updated the `help all` assertion to the
registry-derived string). Broad `check` and trust suites deferred to the
end-of-batch candidate run.
