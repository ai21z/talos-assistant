# [T879-open-low] Help-text accuracy and undocumented command synonyms

Status: open
Priority: low

## Evidence Summary

- Source: /help-surface multi-agent audit (run woar8xy4f)
- Date: 2026-06-26
- Talos version / commit: 0.10.6 / bb8b659a
- Verification status: source-verified, adversarially confirmed

## Findings

Help text disagrees with behavior across several commands (all source-verified):

- Default `/help` calls `/debug` "toggle developer hints" (`HelpCommand.java:115`)
  but `/debug` is a six-level setter: `/debug [off|brief|rag|tools|prompt|trace]
  [on|off]` (`DebugCommand.java:17`), echoing the current level on bare invocation.
- Default `/help` `/clear` override lists only the `/reset` alias and omits `/cls`
  (`HelpCommand.java:116` vs `ClearCommand.java:20` `List.of("cls","reset")`); the
  singular "alias /reset" implies it is the only one.
- `/help debug` calls `/debug prompt on` a "harmless suffix form"
  (`HelpCommand.java:66`) but `on` actively sets PROMPT-level output
  (`DebugCommand.java:36`, `DebugLevel.PROMPT`).
- Undocumented input synonyms accepted but absent from any help surface:
  `/audit enable|disable` (`AuditToggleCommand.java:16-17`; bare `/audit` also does
  not echo current state); `/privacy private enable|disable`
  (`PrivacyCommand.java:40,44`); `/debug retrieval|tool|prompts|frame|all`
  (`DebugLevel.java:40-43`, `DebugCommand.java:52-55`); `/secret delete|rm`
  (only `del` documented; `get` is audit-gated/redacted and `set` double-prompts,
  none documented) (`SecretCommand.java:88,76-83,52-60`).

## Goal

Default-page and topic-page descriptions match real behavior, and each command's
documented input surface matches what it actually accepts (synonyms either
documented or dropped, recorded per command).

## Likely code areas

- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java` (override strings 115-116; debug topic note 66)
- `AuditToggleCommand.java`, `PrivacyCommand.java`, `DebugCommand.java` / `DebugLevel.java`, `SecretCommand.java` (decide the canonical input surface)

## Non-Goals

- No behavior change to the security commands beyond aligning the documented surface; keep fail-closed semantics intact.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- `/debug` and `/clear` default-page descriptions are accurate; the `/debug prompt on` note reflects that it enables PROMPT level.
- For each command with undocumented synonyms, the synonyms are either listed in the usage/help or removed; the decision is recorded in the ticket/changelog.
- Optionally bare `/audit` echoes current state (parity with `/k`, `/debug`).
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.*" --tests "*SimpleCommandsTest" --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop; no version bump. Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.
