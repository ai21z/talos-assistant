# Runtime artifacts

Talos writes local runtime artifacts for diagnostics, traceability, and release evidence. These artifacts are useful evidence, but they are not tamper-evident and are not complete privacy proof.

Current durable sink families and owners:

| Sink family | Primary owner |
|---|---|
| SLF4J/logback file logs | SafeLogFormatter |
| Prompt-debug Markdown | PromptDebugInspector |
| Provider-body JSON | PromptDebugInspector |
| Local trace JSON/text | LocalTurnTraceCapture |
| Session snapshot | JsonSessionStore |
| Turn JSONL | JsonTurnLogAppender |
| Command output summaries | ProcessCommandRunner |
| Synchronized audit bundles | SynchronizedApprovalAuditRunner |
| Manual audit transcripts | manual runbooks and reviewer capture |

Artifact scanning is owned by ArtifactCanaryScanner when release or manual audit roots exist.

Do not commit local prompt-debug captures, provider bodies, model logs, manual audit transcripts, or runtime traces unless a release runbook explicitly asks for a sanitized evidence packet.

## Review order

When judging a live turn, prefer final workspace state, command output, verifier output, tool results, trace records, prompt-debug evidence, provider bodies, and server logs before final assistant prose.

If an artifact is stale, generated from a dirty tree, or tied to the wrong executable, mark it as contaminated evidence instead of repairing the report by hand.
