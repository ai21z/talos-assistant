# T137 - Bounded Process Command Runner

Severity: high
Status: open

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

- Focused runner tests using tiny local commands.
- `.\gradlew.bat --no-daemon build installDist`.
