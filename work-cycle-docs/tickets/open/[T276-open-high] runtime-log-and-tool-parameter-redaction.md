# T276 - Runtime Log and Tool Parameter Redaction

Status: still-open - focused implementation complete; broader runtime log audit remains required
Severity: high / P0 for sensitive beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Tool results may be sanitized while logs still persist raw tool parameters, command output, exception text, protected paths, or user/tool canaries.

## Evidence from current code

- `ProtectedContentPolicy.sanitizeToolParameters`, `sanitizeMap`, and `sanitizeForLog` exist.
- `SafeLogFormatter` wraps log values, maps, protected path tokens, and exception messages.
- `ToolCallExecutionStage` debug parameter/result logs use central sanitization.
- `ProcessCommandRunner` command output redaction delegates to `ProtectedContentPolicy`.
- `ToolCallParser`, `RagService`, and `Indexer` touched call sites use safe formatting for the high-risk paths updated in this pass.

## Evidence from tests/audits

- `SensitiveLogRedactionTest`

## User impact

Private values can leak into local logs even when final answers are clean.

## Product risk

High for developer beta; P0 for sensitive/private-document beta.

## Runtime boundary affected

Debug logs, command stdout/stderr, tool-call params, approval details, exception messages, RAG trace summaries.

## Non-goals

- Do not remove useful diagnostics.
- Do not pretend old local logs are already clean.

## Required behavior

All sensitive tool parameters and generated output logs use central redaction helpers.

## Proposed implementation

Continue replacing raw log formatting with safe summaries and add focused tests for new surfaces.

## Tests

- `debug_log_sanitizes_tool_parameters`
- `command_trace_sanitizes_stdout_stderr_canaries`
- `malformed_tool_payload_log_is_redacted`
- `exception_message_logs_redact_canaries`
- future log-capture tests for approval and RAG trace summaries

## Acceptance criteria

- No raw file-discovered canary in generated logs/artifacts.
- Logs retain enough path/action metadata for audit without raw protected values.

## Rollback / migration notes

Existing logs may already contain raw content; users should purge old debug artifacts for clean audits.

## Open questions

- Should there be a built-in log/artifact purge command?

## Related files

- `src/main/java/dev/talos/runtime/policy/ProtectedContentPolicy.java`
- `src/main/java/dev/talos/runtime/policy/SafeLogFormatter.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/command/ProcessCommandRunner.java`
- `work-cycle-docs/reports/log-redaction-audit.md`
