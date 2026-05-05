# T134 - Command Execution Architecture Design

Severity: medium
Status: done

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

- Documentation created:
  `docs/architecture/10-command-execution-architecture-design.md`
- External references checked:
  OWASP LLM06 Excessive Agency, OWASP LLM02 Sensitive Information Disclosure, MITRE CWE-78, Microsoft PowerShell script injection guidance, Oracle Java ProcessBuilder API, OpenAI agent safety guidance, Anthropic computer-use guidance.
- Local architecture cross-reference checked:
  `TurnProcessor`, `DeclarativePermissionPolicy`, `ProtectedPathPolicy`, `Sandbox`, `ApprovalGate`, `CheckpointService`, `ToolOperationMetadata`, `LocalTurnTraceCapture`, and the capability-growth guardrails.
- `git diff --check` passed with only existing line-ending warnings.

## Completion Notes

- Designed command execution as typed command profiles, not generic shell.
- Defined V1-supported use cases, explicit non-goals, risk classification,
  permission/approval behavior, cwd limits, timeout and output caps,
  environment redaction, network policy, checkpoint rules, trace events,
  result shape, verification matrix, and follow-up implementation tickets.
- No production `run_command` tool was added.
