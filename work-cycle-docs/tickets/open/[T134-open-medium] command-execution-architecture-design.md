# T134 - Command Execution Architecture Design

Severity: medium
Status: open

## Problem

Talos eventually needs approval-gated command execution to become a strong development assistant. But shell/command execution is more dangerous than file reads and writes. It should not be added as a normal tool without a command policy.

## Scope

- Design, but do not implement, command execution.
- Define command risk classification.
- Define allow/deny/ask policy.
- Define cwd limits, timeout, output caps, environment redaction, network policy, and checkpoint rules.
- Define trace events and final outcome behavior.
- Define first supported command use cases, such as test/build/read-only diagnostics.

## Acceptance

- Written command execution design is committed.
- Design cites relevant local architecture and external agent/security sources.
- Design includes ticket sequence for implementation.
- Design explicitly says what commands are out of scope for V1.
- No `run_command` implementation is added in this ticket.

## Non-Goals

- No shell tool implementation.
- No command allowlist in production runtime.
- No background process manager.

## Verification

- Documentation review.
- `git diff --check`.
