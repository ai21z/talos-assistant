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

## 2026-05-20 focused call-site hardening

The current stabilization wave added a second narrow source-scan regression for
high-risk user/model/workspace-derived log values:

- fuzzy/alias tool-name rescue logs in `ToolRegistry`;
- `FileEditTool` trailing-commentary sanitizer path diagnostics;
- `FileWriteTool` trailing-commentary sanitizer path diagnostics;
- `ScoreThresholdReranker` dropped-candidate path diagnostics.

Those call sites now use `SafeLogFormatter.value(...)` for the dynamic values.
This is not the broad T283 live log-capture audit; it is a focused hardening
slice for known raw string/path logging surfaces found during backlog
stabilization.

The follow-up slice also safe-formats additional diagnostics:

- first-run sentinel write failures;
- embedding remote-host and endpoint diagnostics;
- Lucene vector-skip path diagnostics;
- model-not-found warning logs in the assistant executor and tool-loop reprompt
  stage;
- missing-path tool-call support warnings.

Embedding failure exception messages no longer include `inputPreview` or raw
provider error body text. They preserve endpoint/status evidence using
`bodyHash=sha256:...`, `bodyChars=...`, `messageHash=sha256:...`, and
`messageChars=...` summaries.

## 2026-05-20 emitted diagnostic capture follow-up

The next focused slice added deterministic emitted-diagnostic evidence instead
of only source-string assertions:

- `EmbeddingsClientDiagnosticTest.embeddingDebugLogsDoNotEchoProviderBodyOrInputText`
  launches a forked JVM with Logback, captures `EmbeddingsClient` DEBUG output,
  and verifies backend non-2xx logs keep endpoint/status evidence while omitting
  raw provider body text and embedded input text.
- `ProcessCommandRunnerTest.internalFailureRedactsProtectedExecutablePath`
  verifies process-startup failure diagnostics redact protected executable paths
  and file-discovered canary fragments before returning the internal failure.

`EmbeddingsClient` now logs provider-body diagnostics as hash/length summaries
instead of even a redacted body preview. This is stricter than regex redaction:
ordinary provider echoes that are not secret-shaped no longer enter DEBUG logs.
`ProcessCommandRunner` now formats startup exception messages through
`SafeLogFormatter.throwableMessage(...)`.

## 2026-05-20 provider/backend diagnostic boundary follow-up

The next sink-safety slice removes raw provider-body previews from typed backend
exceptions and durable malformed-response trace events:

- `EngineException.ResponseError` now records HTTP status plus `bodyHash` and
  `bodyChars`; its message no longer embeds the raw response body.
- `EngineException.MalformedResponse` now records context plus `bodyHash` and
  `bodyChars`; `bodyPreview()` remains present for source compatibility but
  returns an empty string.
- `LocalTurnTraceCapture.recordBackendMalformedResponse(...)` records
  `context`, `bodyHash`, and `bodyChars` only. It no longer writes a
  `bodyPreview` field to local trace events.
- `AssistantTurnExecutor` continues to show a user-facing malformed-engine
  failure, but does not pass provider-body preview text into trace capture.

This is deterministic sink hardening, not T283 closure. T283 still requires a
focused installed-product audit that captures real logs, prompt-debug files,
provider-body saves, local traces, session/turn artifacts, command-profile
failure output, and terminal transcripts under fresh scan roots.

## 2026-05-20 focused installed-product provider/backend audit

Focused installed-product evidence now covers the provider/backend failure
portion of T283:

```text
Audit id: t283-installed-live-20260520-215141-r2
Branch: v0.9.0-beta-dev
Commit: ae07ef6daf46602b06eff51623e47b314c2b6949
Version: talosVersion=0.9.9
Installed executable: %LOCALAPPDATA%\Programs\talos\bin\talos.bat
Model/backend label: llama_cpp/t283-mock
Fresh Talos home: local/manual-testing/t283-installed-live-20260520-215141-r2/home
Fresh workspace: local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced
```

The run used a local OpenAI-compatible mock provider with two forced paths:

- HTTP 500 response body containing fixture-only canaries;
- HTTP 200 streaming response with malformed SSE data containing fixture-only
  canaries.

The mock-provider logs recorded request/response hashes and lengths only. The
HTTP 500 transcript showed `bodyHash` and `bodyChars` only. The malformed
response created a local trace event `BACKEND_MALFORMED_RESPONSE_CAPTURED` with
`bodyHash` and `bodyChars`, and no durable artifact contained `bodyPreview`.

The runtime artifact scan passed over the fresh audit roots with only the raw
fixture files allowlisted:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t283-installed-live-20260520-215141-r2,local/manual-workspaces/t283-installed-live-20260520-215141-r2" "-PartifactScanAllowlist=local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/.env,local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/protected/private-notes.md,local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/provider-fixtures/response-500.txt,local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/provider-fixtures/response-malformed.txt" --no-daemon
```

This does not close the full broad audit. Remaining live evidence is still
needed for command-profile failure output, synchronized/manual audit bundles,
and the broader two-model prompt-bank run.

## 2026-05-20 focused installed-product command-profile sink audit

Focused installed-product evidence now covers the command-profile failure
portion of T283:

```text
Audit id: t283-command-profile-20260520-220959
Branch: v0.9.0-beta-dev
Commit: ae07ef6daf46602b06eff51623e47b314c2b6949
Version: talosVersion=0.9.9
Installed executable: %LOCALAPPDATA%\Programs\talos\bin\talos.bat
Model/backend label: llama_cpp/t283-command-mock
Fresh Talos home: local/manual-testing/t283-command-profile-20260520-220959/home
Fresh workspace: local/manual-workspaces/t283-command-profile-20260520-220959/command-fixture
```

The run used a local OpenAI-compatible mock provider that recorded request and
response hashes/lengths only. It forced command-tool paths for:

- `gradle_test` in a workspace without a Gradle wrapper;
- an injected raw command-shape payload containing both `profile=gradle_test`
  and forbidden `command=cmd.exe /c dir`;
- `gradle_test` with `cwd=..`.

The installed runtime rejected all three before approval and before process
execution. Each case captured a redirected terminal transcript, `/last trace`,
prompt-debug Markdown, provider-body JSON, isolated `~/.talos/logs`, session
artifacts, turn JSONL, mock-provider hash/length log, workspace status, and
workspace diff. The two direct raw-command wording attempts are retained as
additional evidence that the tool surface can fail even earlier by withholding
`talos.run_command`; the authoritative raw-shape planner evidence is
`raw-command-shape-injected-r3`.

Verification:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t283-command-profile-20260520-220959,local/manual-workspaces/t283-command-profile-20260520-220959" "-PartifactScanAllowlist=local/manual-workspaces/t283-command-profile-20260520-220959/command-fixture/.env" --no-daemon
rg --hidden -n "<body-preview-field>|<fixture-secret-marker>|<fixture-env-key>|<fixture-private-fact>" local\manual-testing\t283-command-profile-20260520-220959 local\manual-workspaces\t283-command-profile-20260520-220959
```

Results:

- Runtime artifact canary scan passed over the fresh audit roots with only the
  fixture `.env` allowlisted.
- Hidden raw-string search found the protected canaries only in the source
  fixture `.env`.
- `bodyPreview` did not appear in the focused audit roots.
- All Talos process exit codes were `0`; workspace diffs were empty.

## 2026-05-20 synchronized approval artifact-bundle rebaseline

Fresh synchronized approval evidence after the sink-hardening wave:

```text
Audit id: t306-t313-sync-rebaseline-20260520-221208
Mode: SCRIPTED
Scenarios: 32
Artifact scan: PASS
```

Each scenario bundle includes final answer, approvals JSONL, model transcript,
trace JSON, trace text, prompt-debug Markdown, provider-body JSON, session
snapshot, turn JSONL, audit-transcript JSON, workspace status, and workspace
diff. The fresh packet contains 32 provider bodies, 32 prompt-debug Markdown
files, 32 trace JSON files, 32 trace text files, 32 session snapshots, 32 turn
JSONL files, and 32 audit bundles.

Verification:

```powershell
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208/artifacts" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208,local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon
```

This still does not close the full broad audit. Remaining release evidence is
the lane-labeled two-model prompt-bank run, with approval-sensitive cases kept
out of blind redirected stdin.

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
| High-risk user/model/workspace log values | `high_risk_user_controlled_log_values_use_safe_formatter` | selected tool-name/path/retrieval candidate diagnostics safe-format dynamic values |
| Broader runtime diagnostics | `broader_runtime_diagnostics_safe_format_paths_models_and_endpoint_values` | selected path/model/endpoint diagnostics safe-format dynamic values |
| Embedding failure diagnostics | `embeddingFailureMessageIncludesEndpointAttemptsWithoutEchoingInputText` | endpoint/status evidence is retained without input text or raw provider body echo |
| Emitted embedding DEBUG logs | `embeddingDebugLogsDoNotEchoProviderBodyOrInputText` | forked Logback capture proves provider-body previews are not emitted raw |
| Command startup failure diagnostics | `internalFailureRedactsProtectedExecutablePath` | protected executable path and canary fragments are redacted in internal failures |
| Provider response errors | `EngineExceptionTest` | non-2xx provider bodies are represented by hash/length, not raw text |
| Malformed provider responses | `EngineExceptionTest`, `AssistantTurnExecutorTest` | malformed backend bodies are represented by hash/length and local trace events omit `bodyPreview` |
| Provider-body save redaction | `PromptDebugInspectorProtectedPathParityTest` | provider-body JSON redacts ordinary private-document fact canaries, not only secret-shaped values |
| Sink inventory drift | `RuntimeSinkSafetyInventoryTest` | release sink inventory names current durable sink families and owners |

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
| Provider clients | Ollama/compat schema and stream parse exception logs now use safe formatting; embedding non-2xx DEBUG logs now use body hash/length summaries | Needs live-audit artifact scan to prove provider-body captures are redacted |
| Engine exceptions / malformed-response traces | non-2xx and malformed provider bodies are hash/length only; local trace captures no `bodyPreview` | Needs live installed-product malformed/provider failure evidence |
| CLI diagnostics | User-visible local diagnostics may print paths/questions intentionally | Must not be treated as persistent log safety without a separate UX policy |
| `ToolRegistry` / `FileEditTool` / `FileWriteTool` / `ScoreThresholdReranker` | Selected user/model/path-derived debug values now use `SafeLogFormatter.value(...)` | This is source-scan evidence only; live debug-log capture remains open |
| `EmbeddingsClient` | Failure diagnostics and captured DEBUG logs now use hash/length summaries instead of embedded text previews or raw provider bodies | Standard-model live backend failure capture remains useful |
| `ProcessCommandRunner` | Captured stdout/stderr are redacted and process-startup internal failures now safe-format exception messages; focused installed command-profile sink audit passed in `t283-command-profile-20260520-220959` | Broader two-model prompt-bank command-boundary evidence still needed |
| `TerminalFirstRun` / `LuceneStore` / model-not-found paths / `ToolCallSupport` | Selected path/model/tool-name diagnostics now use safe formatting | Further raw-value scans should be added as new risky call sites are found |

## 5. Decision

Focused log redaction improved materially, and the current source scan no longer finds raw `LOG.* getMessage()`/`e.toString()` call sites outside safe formatting. Deterministic emitted-log evidence covers the highest-risk embedding provider body path, deterministic command evidence covers process-startup failure messages, the focused installed-product provider/backend audit passed for `t283-installed-live-20260520-215141-r2`, the focused command-profile sink audit passed for `t283-command-profile-20260520-220959`, and the synchronized approval artifact-bundle rebaseline passed for `t306-t313-sync-rebaseline-20260520-221208`. This is still not a full release proof because the lane-labeled two-model prompt-bank run remains open.

## 6. Tests

Focused command that passed before this report update:

`./gradlew.bat test --tests "*SensitiveLog*" --no-daemon`

Fresh focused command from the 2026-05-20 call-site hardening slice:

`./gradlew.bat test --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon`

Fresh focused commands from the follow-up embedding/log diagnostic slice:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest.embeddingFailureMessageIncludesEndpointAttemptsWithoutEchoingInputText" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest.broader_runtime_diagnostics_safe_format_paths_models_and_endpoint_values" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest" --tests "dev.talos.core.embed.EmbeddingsVectorValidationTest" --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

Fresh focused commands from the emitted-log/command-failure slice:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest.embeddingDebugLogsDoNotEchoProviderBodyOrInputText" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.command.ProcessCommandRunnerTest.internalFailureRedactsProtectedExecutablePath" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest" --tests "dev.talos.core.embed.EmbeddingsVectorValidationTest" --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.runtime.command.ProcessCommandRunnerTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

Fresh focused commands from the provider/backend sink-safety slice:

```powershell
.\gradlew.bat test --tests "dev.talos.spi.EngineExceptionTest" --tests "dev.talos.engine.compat.CompatChatClientTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed" --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --tests "dev.talos.release.RuntimeSinkSafetyInventoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.spi.EngineExceptionTest" --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --tests "dev.talos.runtime.JsonSessionStoreTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --tests "dev.talos.release.RuntimeSinkSafetyInventoryTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed" --no-daemon
```

The broader focused bundle also passed:

`./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommandTest" --tests "*SensitiveWorkspaceDetectorTest" --tests "*ArtifactCanary*" --tests "*ConfigPrivacyDefaultsTest" --tests "*UnsupportedFinalAnswer*" --tests "*SensitiveLog*" --no-daemon`
