# T137 - Bounded Process Command Runner

Severity: high
Status: done

## Problem

Before exposing a command tool, Talos needs a process runner that enforces
timeouts, output caps, cwd containment, minimal environment, and redaction.

## Scope

- Add `CommandRunner` and `ProcessCommandRunner`.
- Use `ProcessBuilder` with argv lists, not shell strings.
- Enforce timeout and idle timeout.
- Capture stdout/stderr with byte caps.
- Redact secret-like output and environment values.
- Kill timed-out processes.
- Keep runner internal and unregistered as a tool.

## Acceptance

- Tests cover success, non-zero exit, timeout, output truncation, redaction,
  cwd handling, and no inherited stdin.
- Runner accepts only a validated `CommandPlan`.
- No model-facing command tool is exposed.

## Non-Goals

- No generic shell.
- No background process manager.
- No command approval UI yet.

## Verification

- Red focused test first failed on missing `CommandResult` and
  `ProcessCommandRunner`.
- Focused runner tests passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.runtime.command.ProcessCommandRunnerTest`
- Focused command package tests passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.runtime.command.ProcessCommandRunnerTest --tests dev.talos.runtime.command.CommandArgumentPolicyTest --tests dev.talos.runtime.command.CommandProfileRegistryTest`
- Full verification passed:
  `.\gradlew.bat --no-daemon build installDist`

## Completion Notes

- Added `CommandRunner`, `CommandResult`, and internal-only
  `ProcessCommandRunner`.
- Runner uses argv-only `ProcessBuilder` from a validated `CommandPlan`.
- Runner sets a minimal allowlisted environment, captures stdout/stderr with
  byte caps, redacts secret-like assignments, handles non-zero exit codes, and
  kills timed-out processes.
- Tests use fixed Java subprocesses only; no shell execution is introduced.
- No approval UI or `talos.run_command` tool was added.
