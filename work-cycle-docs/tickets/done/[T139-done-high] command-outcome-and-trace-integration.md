# T139 - Command Outcome And Trace Integration

Severity: high
Status: done

## Problem

Command results must be runtime-owned and failure-dominant. The trace must show
the command lifecycle without leaking secrets or uncapped output.

## Scope

- Add command trace events from the T134 design.
- Integrate command result facts into final outcome rendering.
- Ensure denied, failed, timed-out, and non-zero command results suppress model
  success prose.
- Redact and cap output in trace.

## Acceptance

- Trace records command plan, policy decision, approval, start, completion,
  timeout/failure, output truncation, and redaction status.
- Final output is failure-dominant for denied/failed/timed-out commands.
- Model-authored "tests passed"/"complete" prose is not shown after failure.
- Successful command output preserves concise runtime-owned summary.

## Non-Goals

- No new command profiles.
- No generic shell.

## Verification

- Focused outcome and trace tests.
- `.\gradlew.bat --no-daemon build installDist`.
