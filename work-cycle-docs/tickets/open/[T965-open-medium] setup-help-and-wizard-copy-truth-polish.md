# [T965-open-medium] Setup Help And Wizard Copy Truth Polish

Status: open
Priority: medium

## Evidence Summary

- Source: installed-product release-confidence audit and setup smoke
- Date: 2026-07-04/05 Europe/Madrid
- Talos version / commit: 0.10.8 / 3369b237b297320915dad9dc25aa70769b2a4027
- Installed executable: `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Audit report: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\RELEASE-CONFIDENCE-SUMMARY.md`
- Related done tickets: T926, T948

Redacted prompt/command sequence:

```powershell
talos setup models --help
talos run --help
talos setup wizard --dry-run
```

Expected behavior:

```text
Help flags render normal help without an "Unknown option" error prefix or
non-zero failure status. Windows wizard dry-run copy should not call a detected
Windows .exe a Linux-compatible llama-server.
```

Observed behavior:

```text
`talos setup models --help` printed usable help but first emitted
`Unknown option: '--help'` and exited non-zero. The same issue was previously
observed for `talos run --help`. Windows setup wizard dry-run referred to a
detected `.exe` server path as Linux-compatible.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `UNSUPPORTED_CAPABILITY`

Blocker level:

- candidate follow-up

Why this level:

```text
This does not corrupt files or bypass policy, but it is public first-run polish
on setup/help surfaces immediately adjacent to release onboarding.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Ignore it because the help text is still visible.
```

Architectural hypothesis:

```text
Subcommand help is parsed after option rejection for some command shapes, and
wizard copy uses a platform-neutral or wrong-platform label for detected server
paths.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/commands/SetupCommand.java`
- `src/main/java/dev/talos/cli/commands/RunCmd.java`
- setup wizard dry-run renderer/copy tests
- CLI command parser tests

Why a one-off patch is insufficient:

```text
Help behavior should be uniform across root commands and subcommands. Wizard copy
must be platform-aware wherever it describes local binaries.
```

## Goal

```text
CLI help and setup wizard copy should be truthful, platform-aware, and free of
false error prefixes in normal help flows.
```

## Non-Goals

- No changing setup model behavior from T963/T964.
- No changing installer artifact behavior.
- No broad CLI parser rewrite unless focused tests prove the seam requires it.

## Implementation Notes

- Add parser/CLI tests for `talos setup models --help` and `talos run --help`.
- Add wizard dry-run copy test for Windows server path labels.
- Prefer central help handling if current command parser supports it.

## Architecture Metadata

Capability:

- CLI help and setup wizard guidance.

Operation(s):

- help render
- setup dry-run render

Owning package/class:

- CLI command parser and setup wizard renderer.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low/medium UX risk.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: CLI output tests.
- Verification profile: not applicable.
- Repair profile: not applicable.

Outcome and trace:

- Outcome/truth warnings: help must not report unknown option for valid help.
- Trace/debug fields: unchanged.

Refactor scope:

- Allowed: narrow command parser/help handling and copy text update.
- Forbidden: broad command hierarchy rewrite.

## Acceptance Criteria

- `talos setup models --help` exits successfully and does not print
  `Unknown option`.
- `talos run --help` exits successfully and does not print `Unknown option`.
- Windows setup wizard dry-run labels a Windows `.exe` server path correctly.
- Linux/WSL wizard copy remains correct.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- CLI test: `setup models --help`.
- CLI test: `run --help`.
- Wizard renderer test: Windows server path label.

Manual/TalosBench rerun:

- Prompt family: installed setup/help smoke.
- Workspace fixture: any empty workspace.
- Expected outcome: clean help/copy.

Commands:

```powershell
.\gradlew.bat test --tests "*Setup*" --tests "*Command*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Behavior-changing closeout requires a CHANGELOG entry under `## [Unreleased]`.
- This can follow the release blockers unless a setup/help command becomes part
  of the next public artifact smoke.

## Known Risks

- Some parser libraries treat help as an exception. The fix should distinguish
  help exceptions from real parse errors without hiding bad options.

## Known Follow-Ups

- Re-run installed help smoke before public release notes are published.
