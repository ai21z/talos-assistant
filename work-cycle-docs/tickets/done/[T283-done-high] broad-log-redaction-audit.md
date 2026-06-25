# T283 - Broad Log Redaction Audit

Status: done
Severity: high / P0 for sensitive beta
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-20
Owner: unassigned

## Problem

Helper methods are not proof that every log call is safe. Runtime logs may still expose raw user queries, protected paths, provider exception messages, command details, or model text if call sites bypass redaction.

## Evidence from current code

This pass adds `SafeLogFormatter` and routes several tool execution, parser, RAG, indexer, and tool exception logs through it. Grep still finds remaining log sites in providers, session store, CLI diagnostics, and mode retry paths that need deeper review.

## Evidence from tests/audits

`SensitiveLogRedactionTest` covers tool params, malformed payloads, protected paths, command output canaries, and exception-message redaction.

## User impact

Sensitive user strings should not persist in logs just because a tool failed or a provider returned an error.

## Product risk

Raw logs undermine local trust even when final answers and prompt-debug artifacts are redacted.

## Runtime boundary affected

Tool execution logs, parser logs, provider logs, RAG/index logs, session/trace persistence, command logs.

## Non-goals

- Removing all diagnostics.
- Hiding local approval prompts from the user.

## Required behavior

- Classify every `LOG.debug/info/warn/error` call.
- Redact tool parameters, protected paths, command output, provider body previews, and exception messages.
- Keep a report of fixed versus ticketed call sites.

## Proposed implementation

Continue converting risky call sites to `SafeLogFormatter` or more specific structured summaries.

## Tests

- `SensitiveLogRedactionTest`
- future log-capture tests for provider, RAG trace, command plan, and session persistence logs

## Acceptance criteria

- `work-cycle-docs/reports/log-redaction-audit.md` lists every risky class and disposition.
- No raw `FILE_DISCOVERED_CANARY` appears in generated log artifacts during focused tests.

## Remaining blockers

- Broad provider/session/CLI log-capture tests are not complete.

## Open questions

- Should Talos adopt a structured safe logging wrapper and ban raw `LOG.*` for runtime classes?

## Related files

- `src/main/java/dev/talos/runtime/policy/SafeLogFormatter.java`
- `src/test/java/dev/talos/runtime/policy/SensitiveLogRedactionTest.java`
- `work-cycle-docs/reports/log-redaction-audit.md`

## 2026-05-15 final pre-beta update

High-risk raw exception-message log call sites were converted to `SafeLogFormatter` in this pass, including parser, session/turn persistence, RAG/index, provider parse, and retry/failure paths. `SensitiveLogRedactionTest.no_log_callsite_uses_raw_exception_message` now source-scans for raw `LOG.* getMessage()`/`e.toString()` patterns without safe formatting.

This ticket remains open because live provider/backend failure logs have not been exercised under the two-model audit and command/provider failure paths still need runtime log-capture evidence.

## 2026-05-20 focused stabilization update

Focused source-scan hardening now covers selected raw dynamic value logs in
`ToolRegistry`, `FileEditTool`, `FileWriteTool`, and `ScoreThresholdReranker`.
The regression is:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

This reduces the obvious raw string/path logging surface but does not close the
broad audit. Remaining work is live log-capture evidence for provider/backend
failures, command failures, session/trace persistence failures, and any
debug-enabled run that touches private-document or protected-file canaries.

## 2026-05-20 follow-up diagnostic hardening

Embedding failure exceptions no longer include raw embedded input previews or raw
provider error body text. They retain endpoint/status diagnostics using
hash/length summaries. Selected first-run, Lucene, model-not-found, and
tool-call support logs also now safe-format dynamic path/model/tool strings.

Regression evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest" --tests "dev.talos.core.embed.EmbeddingsVectorValidationTest" --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

The broad audit remains open because this is not yet live provider/backend
failure log evidence across the standard local models.

## 2026-05-20 deterministic emitted-log follow-up

The audit now has one deterministic emitted-log proof instead of only source
inspection: `EmbeddingsClientDiagnosticTest.embeddingDebugLogsDoNotEchoProviderBodyOrInputText`
runs a forked JVM with Logback, captures `EmbeddingsClient` DEBUG output, and
verifies non-2xx provider body echoes do not appear raw. The implementation logs
provider-body diagnostics as `bodyHash=sha256:...` plus `bodyChars=...`.

The command failure boundary also gained deterministic evidence:
`ProcessCommandRunnerTest.internalFailureRedactsProtectedExecutablePath` proves a
process-start failure cannot return a raw protected executable path or raw
file-discovered canary fragment in the internal failure message.

Regression evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest.embeddingDebugLogsDoNotEchoProviderBodyOrInputText" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.command.ProcessCommandRunnerTest.internalFailureRedactsProtectedExecutablePath" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest" --tests "dev.talos.core.embed.EmbeddingsVectorValidationTest" --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.runtime.command.ProcessCommandRunnerTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
```

Remaining blockers:

- live standard-model provider/backend failure log capture;
- session/trace persistence failure capture;
- runtime artifact scan over a focused live log/audit directory.

## 2026-05-20 provider/backend sink-safety follow-up

The broad audit now has deterministic proof that raw provider bodies are not
kept in typed backend diagnostics or malformed-response trace events:

- `EngineException.ResponseError` uses `bodyHash`/`bodyChars` instead of raw
  response body text.
- `EngineException.MalformedResponse` uses `bodyHash`/`bodyChars`; raw body
  previews are disabled.
- `AssistantTurnExecutor` records malformed backend response evidence in local
  traces without a `bodyPreview` field.
- provider-body prompt-debug redaction covers ordinary private-document fact
  canaries such as names and addresses, not only secret-shaped tokens.
- `work-cycle-docs/reports/runtime-sink-safety-inventory.md` now lists the
  durable sink families, owners, sanitizers, deterministic evidence, live-audit
  status, and remaining blocker.

Regression evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.spi.EngineExceptionTest" --tests "dev.talos.engine.compat.CompatChatClientTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed" --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --tests "dev.talos.release.RuntimeSinkSafetyInventoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.spi.EngineExceptionTest" --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --tests "dev.talos.runtime.JsonSessionStoreTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --tests "dev.talos.release.RuntimeSinkSafetyInventoryTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed" --no-daemon
```

Remaining blockers after this deterministic slice, before the focused
installed-product provider/backend audit below:

- focused installed-product T283 live evidence with fresh Talos home and fresh
  audit roots;
- forced or simulated provider/backend failure path artifact capture;
- command-profile failure path artifact capture;
- session/turn/local-trace artifact capture under real runtime;
- `checkRuntimeArtifactCanaries` over only the focused fresh audit roots.

## 2026-05-20 focused installed-product provider/backend sink audit

Focused installed-product evidence now exists for the provider/backend failure
sink cluster. The authoritative run is:

```text
Audit id: t283-installed-live-20260520-215141-r2
Branch: v0.9.0-beta-dev
Commit: ae07ef6daf46602b06eff51623e47b314c2b6949
Version: talosVersion=0.9.9
Installed executable: %LOCALAPPDATA%\Programs\talos\bin\talos.bat
Installed version output: Talos 0.9.9 - Java 21.0.9+10-LTS - Windows 11 amd64
Isolated Talos home: local/manual-testing/t283-installed-live-20260520-215141-r2/home
Fresh workspace: local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced
Model/backend label: llama_cpp/t283-mock
```

The earlier `t283-installed-live-20260520-214919` run is retained only as
non-authoritative evidence because the isolated config did not set top-level
`llm.model`, so `Config.ensureDefaults()` preserved the display/request model
as `talos-agent`. The corrected `r2` run set both `llm.model` and
`engines.llama_cpp.model` to `t283-mock`.

Evidence captured in `r2`:

- HTTP 500 provider pass with terminal transcript, `/last trace`,
  prompt-debug Markdown, provider-body JSON, isolated `~/.talos/logs`, session
  artifacts, turn JSONL, mock-provider hash/length log, workspace status, and
  workspace diff.
- Malformed streaming provider pass with terminal transcript, `/last trace`,
  prompt-debug Markdown, provider-body JSON, isolated `~/.talos/logs`, session
  artifacts, turn JSONL, mock-provider hash/length log, workspace status, and
  workspace diff.
- The HTTP 500 user-visible failure reports only
  `bodyHash=sha256:f30c8b18daab145964fdbe69dad972deef7501eb144d6f3c3ab44186dd8a48ab`
  and `bodyChars=69`.
- The malformed-response local trace records
  `BACKEND_MALFORMED_RESPONSE_CAPTURED` with `bodyHash` and `bodyChars`; no
  durable artifact contains `bodyPreview`.
- The mock-provider logs record request/response hashes and lengths only, not
  raw provider response bodies.

Verification:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat e2eTest --no-daemon
.\gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t283-installed-live-20260520-215141-r2,local/manual-workspaces/t283-installed-live-20260520-215141-r2" "-PartifactScanAllowlist=local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/.env,local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/protected/private-notes.md,local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/provider-fixtures/response-500.txt,local/manual-workspaces/t283-installed-live-20260520-215141-r2/provider-forced/provider-fixtures/response-malformed.txt" --no-daemon
git diff --check
```

Results:

- `check`, `e2eTest`, `clean installDist`, and `install-windows.ps1 -Force`
  passed before the audit run.
- The runtime artifact canary scan passed over only the fresh `r2` audit roots
  with raw fixture files allowlisted.
- `rg bodyPreview local/manual-testing/t283-installed-live-20260520-215141-r2 local/manual-workspaces/t283-installed-live-20260520-215141-r2`
  returned no matches.
- `git diff --check` exited 0, with line-ending warnings only.

Remaining blockers immediately after this provider/backend-focused pass, before
the later command-profile and synchronized-bundle evidence lane below:

- live command-profile failure sink capture;
- synchronized/manual audit-bundle scan evidence after the sink hardening wave;
- broader two-model prompt-bank audit evidence.

## 2026-05-20 focused command-profile and synchronized-bundle evidence lane

The next evidence lane reduced the T283 blocker again.

Command-profile sink audit:

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

The installed runtime was driven through a local OpenAI-compatible mock provider
that recorded request/response hashes and lengths only. The authoritative
command-boundary cases were:

- `missing-gradle-wrapper`: `talos.run_command` with `profile=gradle_test`
  rejected because the workspace/cwd had no Gradle wrapper.
- `raw-command-shape-injected-r3`: user requested the approved Gradle profile,
  but the mock provider injected a forbidden raw `command` parameter alongside
  `profile=gradle_test`; runtime rejected it as raw shell command shape.
- `cwd-escape`: `talos.run_command` with `profile=gradle_test` and `cwd=..`
  rejected as workspace escape.

All three authoritative cases were rejected before approval and before process
execution. Each captured transcript, `/last trace`, prompt-debug Markdown,
provider-body JSON, isolated logs, session artifacts, turn JSONL, mock-provider
hash/length log, workspace status, and workspace diff. Two direct
raw-command-wording attempts are retained as extra evidence that tool-surface
narrowing can block `talos.run_command` even earlier; the planner-level raw
shape evidence is `raw-command-shape-injected-r3`.

Verification:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t283-command-profile-20260520-220959,local/manual-workspaces/t283-command-profile-20260520-220959" "-PartifactScanAllowlist=local/manual-workspaces/t283-command-profile-20260520-220959/command-fixture/.env" --no-daemon
rg --hidden -n "<body-preview-field>|<fixture-secret-marker>|<fixture-env-key>|<fixture-private-fact>" local\manual-testing\t283-command-profile-20260520-220959 local\manual-workspaces\t283-command-profile-20260520-220959
```

Results:

- Runtime artifact canary scan passed over the fresh command-profile roots with
  only the source fixture `.env` allowlisted.
- Hidden raw-string search found canaries only in the source fixture `.env`.
- `bodyPreview` did not appear in the command-profile audit roots.
- All Talos process exit codes were `0`; workspace diffs were empty.

Synchronized approval artifact-bundle rebaseline:

```text
Audit id: t306-t313-sync-rebaseline-20260520-221208
Mode: SCRIPTED
Scenarios: 32
Artifact scan: PASS
```

The fresh synchronized packet contains 32 scenario bundles. Each bundle includes
final answer, approvals JSONL, model transcript, trace JSON/text, prompt-debug
Markdown, provider-body JSON, session snapshot, turn JSONL, audit-transcript
JSON, workspace status, and workspace diff. The follow-up scan passed:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208,local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon
```

Remaining blocker after this lane:

- broader lane-labeled two-model prompt-bank audit evidence. Approval-sensitive
  prompt-bank cases must not be claimed from blind redirected stdin; they need a
  synchronized/manual lane.

## 2026-05-20 lane-labeled prompt-bank sink evidence

The broader prompt-bank blocker is reduced again, but not closed.

Strict safe redirected-stdin lane:

- GPT-OSS: 19/19 non-approval TalosBench cases passed with strict evidence.
- Qwen: 19/19 non-approval TalosBench cases passed with strict evidence.
- Strict mode captured input script, transcript, `/last trace`,
  `/prompt-debug save`, `/session save`, workspace git baseline, workspace
  status, and workspace diff for each case.
- Runtime artifact canary scan passed over
  `local/manual-testing/lane-bank-safe-20260520` and
  `local/manual-workspaces/lane-bank-safe-20260520` with only source fixture
  canary files allowlisted.

Synchronized approval lane:

- `runSynchronizedApprovalAudit` passed at
  `local/manual-testing/lane-bank-sync-20260520/artifacts`.
- Scenario count: 32.
- Artifact scan: PASS.

True PTY/manual lane:

- Packet prepared at
  `local/manual-testing/lane-bank-pty-manual-20260520/artifacts`.
- A fresh completed packet passed at
  `local/manual-testing/true-pty-manual-20260520-r1/artifacts`.
- `checkRuntimeArtifactCanaries` passed over the completed packet, fixture
  workspace, and the actual prompt-debug output directory.
- `validateSynchronizedApprovalPtyManualAudit` reported `Status: PASS`.
- No raw protected `.env` canary or raw private-document fact appeared in the
  scanned transcript, prompt-debug Markdown, provider-body JSON, trace evidence,
  or report artifacts.
- Caveat: `/prompt-debug save "<absolute Windows path>"` wrote to a mangled
  repo-relative directory. This is tracked as T333 and did not create a leak in
  this run.

Report:

- `work-cycle-docs/reports/lane-labeled-two-model-prompt-bank-audit-20260520.md`

Remaining blocker:

- rerun final clean-candidate evidence before closing T283 as release-grade sink
  proof for a versioned beta packet.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as deferred out of this main-merge line: future private-document / document-beta / v1 / future-capability scope, not current main-merge work.

Closed by independent review as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
