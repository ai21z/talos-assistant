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

Stale artifacts or artifacts bound to the wrong source/executable cannot validate the current candidate. Preserve them with their original identity rather than repairing the report by hand. Dirty-tree diagnostics can support local stabilization when their exact source, executable and configuration are recorded; they are not clean, candidate-bound release qualification.

Prompt-debug saves allocate a separate export directory for each save invocation. Its `index.md` lists the redacted render/provider-body members, including absent bodies, and is written after those members. A failed save may leave a partial group; only a successful command reports the completed export. This prevents ordinary repeated saves from overwriting earlier captures, but is not a multi-file crash-atomic or hostile-directory guarantee.

Generation abort origin and cause are distinct. The runtime watchdog carries its winning typed cause: repetition is reported as `REPETITION_LOOP` (code 500), while idle and wall-clock expiry remain `TIMEOUT` (408) with distinct diagnostics. These are CLI/trace classifications, not evidence of an HTTP response. Legacy watchdog results without a captured cause remain unclassified failures; diagnostic prose does not establish a timeout. The same cause is retained through abnormal settlement and `/last` readback. A partial response is never promoted to a completed answer by this classification.
