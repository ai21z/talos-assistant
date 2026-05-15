# Log Redaction Audit

## 1. Scope

This audit covers runtime/debug log paths that can touch tool parameters, protected paths, command output, provider/request details, RAG traces, session/turn persistence errors, and exception messages.

## 2. Implemented in this pass

- Added `dev.talos.runtime.policy.SafeLogFormatter`.
- Routed tool execution parameter logs through sanitized tool-parameter rendering.
- Routed malformed tool-call payload logs through sanitized value rendering.
- Routed indexer/RAG trace and exception summaries through safe formatting in the touched call sites.
- Added focused regression tests in `SensitiveLogRedactionTest`.

## 3. Covered by tests

| Surface | Test evidence | Result |
|---|---|---|
| Tool parameters | `debug_log_sanitizes_tool_parameters` | Raw canary, secret value, and protected path redacted |
| Malformed tool payload | `malformed_tool_payload_log_is_redacted` | Raw canary and `.env` redacted |
| Command stdout/stderr text | `command_trace_sanitizes_stdout_stderr_canaries` | Raw canary and password value redacted |
| Exception message | `exception_message_logs_redact_canaries` | Protected path and secret assignment redacted |
| Protected path classifier | `debug_log_sanitizes_protected_paths` | `.env`, `secrets/`, and `protected/` recognized |

## 4. Current call-site classification

| Area | Current disposition | Remaining risk |
|---|---|---|
| `ToolCallExecutionStage` | Sanitized for tool params, path hints, duplicate/stale edit logs, and tool result summaries touched in this pass | Additional path-oriented logs should continue using `SafeLogFormatter` |
| `ToolCallParser` | Malformed payload and parse exception logs sanitized in touched paths | `tool_call missing name` still deserves follow-up if provider text can include sensitive payloads |
| `RagService` | Retrieval trace summary and failure reason sanitized in touched paths | Provider/embed exception chains need broader capture tests |
| `Indexer` | Root/path/skip/failure logs sanitized in touched paths | `ExecutionException` stack logging remains a follow-up risk |
| `JsonSessionStore` / `JsonTurnLogAppender` | Persistence content is redacted by existing paths; warning logs need broader audit | Error messages and path arguments should be converted to safe formatting |
| Provider clients | No full provider body logging found in the grep pass, but provider exception messages remain a review area | Needs log-capture tests around provider failure paths |
| CLI diagnostics | User-visible local diagnostics may print paths/questions intentionally | Must not be treated as persistent log safety without a separate UX policy |

## 5. Decision

Focused log redaction improved, but this is not a blanket proof that all logs are safe. T283 remains open for a full call-site-by-call-site migration or a structured safe logging wrapper.

## 6. Tests

Focused command that passed before this report update:

`./gradlew.bat test --tests "*SensitiveLog*" --no-daemon`

The broader focused bundle also passed:

`./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommandTest" --tests "*SensitiveWorkspaceDetectorTest" --tests "*ArtifactCanary*" --tests "*ConfigPrivacyDefaultsTest" --tests "*UnsupportedFinalAnswer*" --tests "*SensitiveLog*" --no-daemon`

