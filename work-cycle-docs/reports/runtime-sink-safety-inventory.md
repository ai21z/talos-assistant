# Runtime Sink Safety Inventory

Date: 2026-05-21

Branch under audit: `T346`

Purpose: keep a release-facing inventory of durable or semi-durable sinks that may receive
model, tool, provider, command, trace, session, or manual-audit content. This is evidence
control, not a new runtime abstraction.

## Sink Inventory

| Sink family | Primary owner | Sanitizer/control | Deterministic evidence | Live-audit status | Remaining blocker |
|---|---|---|---|---|---|
| SLF4J/logback file logs | Runtime, provider, core, and tool call sites | `dev.talos.safety.SafeLogFormatter`, `ProtectedContentSanitizer`, runtime `ProtectedContentPolicy` wrappers | `SensitiveLogRedactionTest`, `SafetyOwnershipTest`, `EmbeddingsClientDiagnosticTest`, `ProcessCommandRunnerTest` | Focused T283 provider/backend installed-product log scan passed for `t283-installed-live-20260520-215141-r2`; focused T283 command-profile installed-product log scan passed for `t283-command-profile-20260520-220959`; T346 moved sink-safe formatting to neutral `dev.talos.safety` with no behavior change | Broader two-model prompt-bank evidence still needs log capture review |
| Prompt-debug Markdown | `PromptDebugInspector` | Protected-path blocks plus `ProtectedContentPolicy.sanitizeText` | `PromptDebugInspectorProtectedPathParityTest` | Focused T283 provider/backend prompt-debug save passed for `t283-installed-live-20260520-215141-r2` | Broader two-model audit still needs prompt-debug coverage |
| Provider-body JSON | `PromptDebugInspector` and provider debug capture flow | `PromptDebugInspector.redactedProviderBodyJson(...)`, `ProtectedContentPolicy.sanitizeText` | `PromptDebugInspectorProtectedPathParityTest` | Focused T283 provider-body save passed for `t283-installed-live-20260520-215141-r2` | Broader two-model audit still needs provider-body coverage |
| Local trace JSON/text | `LocalTurnTraceCapture` | structured metadata plus trace redaction; backend malformed bodies are hash/length only | `AssistantTurnExecutorTest`, `JsonSessionStoreTest` | Focused T283 malformed-response trace passed for `t283-installed-live-20260520-215141-r2`; command-profile trace capture passed for `t283-command-profile-20260520-220959`; 32 synchronized approval trace JSON/text bundles passed for `t306-t313-sync-rebaseline-20260520-221208` | Broader two-model prompt-bank trace evidence still required |
| Session snapshot | `JsonSessionStore` | `ProtectedContentPolicy.sanitizeText` during persistence | `JsonSessionStoreTest` | Focused T283 provider/backend and command-profile session scans passed; 32 synchronized approval session snapshots passed in `t306-t313-sync-rebaseline-20260520-221208` | Broader two-model prompt-bank session evidence still required |
| Turn JSONL | `JsonTurnLogAppender` | `ProtectedContentPolicy.sanitizeText` during turn persistence | `JsonSessionStoreTest` | Focused T283 provider/backend and command-profile turn-log scans passed; 32 synchronized approval turn JSONL files passed in `t306-t313-sync-rebaseline-20260520-221208` | Broader two-model prompt-bank turn evidence still required |
| Command output summaries | `ProcessCommandRunner` | stdout/stderr and startup failures redacted through runtime policy and neutral `SafeLogFormatter` | `ProcessCommandRunnerTest`, `SensitiveLogRedactionTest` | Focused T283 command-profile failure capture passed for `t283-command-profile-20260520-220959` | Broader two-model prompt-bank command-boundary evidence still required |
| Synchronized audit bundles | `SynchronizedApprovalAuditRunner` | generated audit bundle plus `ArtifactCanaryScanner` release scan | synchronized approval runner tests and canary scan tasks | Fresh 32-scenario synchronized rebaseline passed for `t306-t313-sync-rebaseline-20260520-221208` with artifact scan PASS | Full prompt-bank approval-sensitive coverage still needs a synchronized lane |
| Manual audit transcripts | manual ConPTY/JLine transcript capture | runbook discipline plus `ArtifactCanaryScanner` over fresh roots | `RuntimeSinkSafetyInventoryTest` keeps this sink in the release inventory | Focused T283 redirected terminal transcript passed for non-approval provider/backend failure paths in `t283-installed-live-20260520-215141-r2` | True PTY approval-sensitive transcripts remain tracked separately; broader audit transcripts still required |

## Regression Guard

`RuntimeSinkSafetyInventoryTest` fails if this report stops naming the known sink
families or the owner classes that currently control them:

- `dev.talos.safety.SafeLogFormatter`
- `ProtectedContentSanitizer`
- `ProtectedPathTokens`
- `PromptDebugInspector`
- `JsonSessionStore`
- `JsonTurnLogAppender`
- `LocalTurnTraceCapture`
- `ProcessCommandRunner`
- `SynchronizedApprovalAuditRunner`
- `ArtifactCanaryScanner`

## Current Decision

The provider/backend diagnostic boundary now has deterministic evidence and focused
installed-product evidence. Command-profile failure sinks now have focused
installed-product evidence. Synchronized approval bundles now have a fresh 32-scenario
scanned rebaseline. T346 moves pure sink-safe formatting and path-token
recognition to neutral `dev.talos.safety`; runtime `ProtectedContentPolicy`
remains the tool-result and workspace-aware adapter. The remaining release blocker
is narrower: produce lane-labeled two-model prompt-bank evidence, with
approval-sensitive cases routed through a synchronized/manual lane rather than
blind redirected stdin.
