# T276 - Runtime Log and Tool Parameter Redaction

Status: done
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

## 2026-05-20 focused stabilization update

Additional high-risk debug call sites now safe-format user/model/path-derived
values:

- fuzzy/alias tool-name rescue logs in `ToolRegistry`;
- trailing-commentary sanitizer path logs in `FileEditTool`;
- trailing-commentary sanitizer path logs in `FileWriteTool`;
- dropped retrieval candidate path logs in `ScoreThresholdReranker`.

Regression evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

The ticket remains open because this was a focused source-scan slice, not a
broad runtime/provider/command log-capture audit.

## 2026-05-20 follow-up diagnostic hardening

Additional diagnostics now avoid raw dynamic values:

- first-run sentinel write failures;
- embedding remote-host and endpoint diagnostics;
- Lucene vector-skip path diagnostics;
- model-not-found warning logs in `AssistantTurnExecutor` and
  `ToolCallRepromptStage`;
- missing-path tool-call warnings in `ToolCallSupport`.

`EmbeddingsClient` exception messages no longer include embedded-text previews or
raw provider error bodies. Endpoint/status evidence is retained through
hash/length summaries.

Regression evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest" --tests "dev.talos.core.embed.EmbeddingsVectorValidationTest" --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

This reduces persistent diagnostic leak risk, but the broad live log-capture
audit remains open.

## 2026-05-20 emitted-log and command-failure evidence

Deterministic emitted-log evidence now covers the embedding provider failure
path: a forked JVM captures `EmbeddingsClient` DEBUG logs and proves backend
non-2xx provider body text and embedded input text are not emitted raw.
Diagnostics retain endpoint/status evidence through `bodyHash=sha256:...` and
`bodyChars=...`.

Command startup failure diagnostics now pass through
`SafeLogFormatter.throwableMessage(...)`; the regression verifies a protected
executable path with a file-discovered canary is redacted in the returned
internal failure.

Regression evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest.embeddingDebugLogsDoNotEchoProviderBodyOrInputText" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.command.ProcessCommandRunnerTest.internalFailureRedactsProtectedExecutablePath" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest" --tests "dev.talos.core.embed.EmbeddingsVectorValidationTest" --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.runtime.command.ProcessCommandRunnerTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

Remaining evidence is no longer a narrow implementation blocker here; it is the
broader live/runtime artifact audit tracked by T283.

## 2026-05-20 provider/backend sink-safety evidence

Typed provider/backend exceptions now avoid raw provider body persistence:

- `EngineException.ResponseError` exposes HTTP status plus `bodyHash` and
  `bodyChars`; its message no longer carries raw response body text.
- `EngineException.MalformedResponse` exposes context plus `bodyHash` and
  `bodyChars`; `bodyPreview()` is retained for source compatibility but returns
  an empty string.
- `LocalTurnTraceCapture.recordBackendMalformedResponse(...)` records
  `context`, `bodyHash`, and `bodyChars` only, with no `bodyPreview` trace field.
- `PromptDebugInspectorProtectedPathParityTest` now covers ordinary
  private-document fact canaries in saved provider-body JSON.
- `RuntimeSinkSafetyInventoryTest` guards the release sink inventory so known
  durable sink families and owners remain explicit.

Regression evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.spi.EngineExceptionTest" --tests "dev.talos.engine.compat.CompatChatClientTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed" --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --tests "dev.talos.release.RuntimeSinkSafetyInventoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.spi.EngineExceptionTest" --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --tests "dev.talos.runtime.JsonSessionStoreTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --tests "dev.talos.release.RuntimeSinkSafetyInventoryTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed" --no-daemon
```

Current state: deterministic sink hardening is substantially stronger. This
ticket remains open only because the release gate still requires live installed
artifact evidence under T283.

## 2026-05-20 focused installed-product provider/backend evidence

T283 now has focused installed-product provider/backend sink evidence:

```text
Audit id: t283-installed-live-20260520-215141-r2
Branch: v0.9.0-beta-dev
Commit: ae07ef6daf46602b06eff51623e47b314c2b6949
Version: talosVersion=0.9.9
Installed executable: %LOCALAPPDATA%\Programs\talos\bin\talos.bat
Model/backend label: llama_cpp/t283-mock
```

The audit forced HTTP 500 and malformed streaming provider responses containing
raw fixture canaries, saved prompt-debug/provider-body artifacts, captured local
trace/session/turn/log artifacts under an isolated Talos home, and passed
`checkRuntimeArtifactCanaries` over the fresh audit roots with only the fixture
files allowlisted.

This ticket should remain in its current state rather than being closed
independently: command-profile failure sink capture, synchronized/manual audit
bundle evidence, and broader two-model prompt-bank evidence are still tracked by
T283.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as deferred out of this main-merge line: future private-document / document-beta / v1 / future-capability scope, not current main-merge work.

Closed by Opus as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
