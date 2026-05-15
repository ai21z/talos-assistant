# Log Redaction Audit

## 1. Scope

This audit covers runtime/debug log paths that can touch tool parameters, protected paths, command output, provider/request details, RAG traces, session/turn persistence errors, and exception messages.

## 2. Implemented in this pass

- Added `dev.talos.runtime.policy.SafeLogFormatter`.
- Routed tool execution parameter logs through sanitized tool-parameter rendering.
- Routed malformed tool-call payload logs through sanitized value rendering.
- Routed indexer/RAG trace and exception summaries through safe formatting in the touched call sites.
- Routed session/turn persistence warning logs through safe path/value/exception rendering.
- Routed provider schema/stream parse exception logs through safe exception rendering.
- Suppressed raw tool-exception stack trace logging in `TurnProcessor`; the log now records a sanitized reason only.
- Added source-audit regression coverage that fails if a `LOG.*` line uses raw `getMessage()`/`e.toString()` without `SafeLogFormatter`.
- Added focused regression tests in `SensitiveLogRedactionTest`.

## 3. Covered by tests

| Surface | Test evidence | Result |
|---|---|---|
| Tool parameters | `debug_log_sanitizes_tool_parameters` | Raw canary, secret value, and protected path redacted |
| Malformed tool payload | `malformed_tool_payload_log_is_redacted` | Raw canary and `.env` redacted |
| Command stdout/stderr text | `command_trace_sanitizes_stdout_stderr_canaries` | Raw canary and password value redacted |
| Exception message | `exception_message_logs_redact_canaries` | Protected path and secret assignment redacted |
| Protected path classifier | `debug_log_sanitizes_protected_paths` | `.env`, `secrets/`, and `protected/` recognized |
| Tool-call execution params | `all_tool_execution_debug_params_are_sanitized` | `ToolCallExecutionStage` must use `SafeLogFormatter.parameters(...)` |
| Malformed parser call site | `log_callsite_toolcallparser_malformed_payload_redacts_canary` | raw JSON payload logging is blocked |
| Session-store call sites | `log_callsite_json_session_store_redacts_exception_message` | raw `e.getMessage()` removed from session-store log calls |
| Provider exception call sites | `log_callsite_provider_exception_redacts_canary` | provider parse exceptions use `SafeLogFormatter.throwableMessage(...)` |
| Broad raw exception-message source scan | `no_log_callsite_uses_raw_exception_message` | no `LOG.*` line may use raw `getMessage()`/`e.toString()` without safe formatting |

## 4. Current call-site classification

| Area | Current disposition | Remaining risk |
|---|---|---|
| `ToolCallExecutionStage` | Sanitized for tool params, path hints, duplicate/stale edit logs, and tool result summaries touched in this pass | Additional path-oriented logs should continue using `SafeLogFormatter` |
| `ToolCallParser` | `tool_call missing name` now logs `SafeLogFormatter.value(json)` | Continue avoiding raw provider text in future parser diagnostics |
| `ToolCallRepromptStage` | retry/engine exception messages now use `SafeLogFormatter.throwableMessage(...)`; stale path diagnostics use `SafeLogFormatter.value(...)` | User-visible retry messages may still include engine guidance and should be handled by UX policy if needed |
| `AssistantTurnExecutor` | high-risk retry/handoff exception logs now use `SafeLogFormatter` | Some user-visible local answer text still intentionally reports runtime failures |
| `RagService` | Retrieval trace summary, embedding failure reason, retrieval failure, and lazy-indexing failure logs now safe-format values/reasons | Full provider/embed failure-path log-capture tests remain useful |
| `Indexer` / `LuceneStore` / `IndexedWorkspaceSymbolChecker` | root/path/skip/failure/freshness logs now safe-format paths and exception reasons | Low-risk numeric/status logs remain unsanitized by design |
| `JsonSessionStore` / `JsonTurnLogAppender` | session ids, paths, trace ids, file names, and exception messages now use `SafeLogFormatter` | Local UI may still show intentional path targets outside persisted logs |
| Provider clients | Ollama/compat schema and stream parse exception logs now use safe formatting; full provider bodies were not found in the log grep pass | Needs live-audit artifact scan to prove provider-body captures are redacted |
| CLI diagnostics | User-visible local diagnostics may print paths/questions intentionally | Must not be treated as persistent log safety without a separate UX policy |

## 5. Decision

Focused log redaction improved materially, and the current source scan no longer finds raw `LOG.* getMessage()`/`e.toString()` call sites outside safe formatting. This is still not a private-document release proof because live provider/backend failures and live-audit artifact logs have not been exercised with two local models. T283 remains open for log-capture tests around live provider, command, and long-running model failure paths.

## 6. Tests

Focused command that passed before this report update:

`./gradlew.bat test --tests "*SensitiveLog*" --no-daemon`

The broader focused bundle also passed:

`./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommandTest" --tests "*SensitiveWorkspaceDetectorTest" --tests "*ArtifactCanary*" --tests "*ConfigPrivacyDefaultsTest" --tests "*UnsupportedFinalAnswer*" --tests "*SensitiveLog*" --no-daemon`
