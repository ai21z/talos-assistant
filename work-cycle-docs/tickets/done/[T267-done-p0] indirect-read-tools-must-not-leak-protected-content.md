# T267 - Indirect Read Tools Must Not Leak Protected Content

Status: done - implemented for tested developer/text beta boundary
Severity: P0
Release gate: no for this core indirect-read boundary; broader private-document positioning remains gated by T295/T280/T285
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Talos gates direct protected reads better than indirect reads. Before this work, `talos.read_file(".env")` could require approval, but `talos.grep`, slash `/grep`, `talos.retrieve`, and RAG indexing/retrieval could discover and return protected or canary content without the same runtime boundary.

This work cycle implemented the core runtime boundary for the tested indirect-read paths. Broader private-document positioning, private-folder UX, and artifact-surface expansion remain tracked by narrower follow-up tickets instead of keeping this stale umbrella P0 open.

## Evidence from current code

- `ProtectedContentPolicy` now centralizes canary, private marker, and secret-like assignment redaction.
- `ToolCallExecutionStage.execute(...)` sanitizes indirect tool results before appending them to model-loop messages.
- `ToolCallSupport.formatToolResult(...)` sanitizes default tool-result formatting.
- `GrepTool` and slash `GrepCommand` now skip protected files and redact secret/canary lines from normal files.
- `RetrieveTool` and `RagService` now omit/sanitize protected snippets at retrieval time, including dirty-index snippets.
- `Indexer` now applies code-level protected-path and unsupported-format exclusion.
- `default-config.yaml` removes `**/*.env` from includes and adds protected excludes.
- `JsonSessionStore` now redacts JSON text-node values before persisting session, turn JSONL, and trace artifacts.

## Evidence from external/source crosscheck

`work-cycle-docs/reports/t267-source-crosscheck.md` concludes that Codex and Gemini both separate technical boundaries from approval/user review, and that tool outputs are returned to the model. The applicable Talos principle is runtime enforcement before model handoff.

## User impact

A user can ask for a search or retrieval operation and accidentally expose secrets, private markers, or protected folder content to model context, prompt-debug artifacts, provider-body JSON, traces, session logs, and final answers.

## Product risk

Release blocker for any claim that Talos is safe for tax, health, legal, family, admin, or personal paperwork folders. For a narrow developer/text beta, this remains a serious trust-risk unless documented as not suitable for sensitive folders.

## Runtime boundary affected

- Tool-result to model-context boundary
- Search/retrieve result boundary
- RAG indexing and dirty-index retrieval boundary
- Prompt-debug/provider-body/trace/session/log artifact boundary

## Non-goals

- No remote extraction or cloud document processing.
- No prompt-only fix.
- No multi-agent/reviewer replacement for runtime policy.

## Required behavior

- Protected files and directories are skipped, blocked, or safely summarized by indirect read tools.
- Raw project canary prefix patterns, private-marker values, and secret-like assignment values from indirect read tools never enter model context or generated artifacts.
- `protected/` is treated as protected for beta trust.
- If matches exist only in protected content, Talos reports that matches were found but lines were withheld.
- Dirty RAG indexes cannot surface raw protected snippets.

## Proposed implementation

Implemented `ProtectedContentPolicy` as the central runtime policy and integrated it in `ToolCallExecutionStage`, `ToolCallSupport`, `GrepTool`, `GrepCommand`, `RetrieveTool`, `RagService`, `Indexer`, `TraceRedactor`, `PromptDebugInspector`, and session/trace persistence.

Residual implementation work is tracked by T272, T280, T285, T295, and the later strict-audit blocker tickets. This ticket is closed for the protected-content indirect-read invariant it originally introduced.

## Tests

- `grep_does_not_leak_env_canary`
- `grep_does_not_leak_env_local_canary`
- `grep_does_not_leak_secrets_directory_canary`
- `grep_does_not_leak_protected_directory_canary`
- `slash_grep_does_not_leak_env_canary`
- `slash_grep_does_not_leak_private_marker`
- `grep_redacts_secret_like_assignment_in_normal_file`
- `grep_redacts_private_marker_in_normal_file`
- `retrieve_does_not_leak_env_canary`
- `retrieve_does_not_leak_dirty_index_canary`
- provider-body/prompt-debug/trace/session canary tests
- generated artifact canary scan

## Acceptance criteria

- No raw T267 canary appears outside fixture/test/spec allowlists in generated build/local artifacts.
- Grep, slash grep, retrieve, RAG index/retrieval, prompt-debug, provider-body, trace, session, turn JSONL, and final answers pass focused canary assertions.
- `./gradlew.bat clean check e2eTest --no-daemon` passes.
- Private-document release gate also requires live audit artifacts and private-folder-mode decision/implementation.

## Rollback / migration notes

Existing RAG indexes may contain protected content. Implement retrieval-time sanitization and consider index invalidation/versioning.

## Open questions

- Should private-folder mode make `protected/`, `private/`, `tax/`, `health/`, and `legal/` stricter by default?
- Should protected-path matches report path-only or count-only metadata?

## Related files

- `src/main/java/dev/talos/runtime/policy/ProtectedPathPolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java`
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
- `src/main/java/dev/talos/cli/repl/slash/GrepCommand.java`
- `src/main/java/dev/talos/tools/impl/RetrieveTool.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/main/resources/config/default-config.yaml`

## 2026-05-15 hardening update

Additional implementation completed:

- Approved direct protected reads now have explicit scope policy.
- Private mode defaults approved protected direct reads to local-display-only model handoff.
- Tool-call debug parameter formatting now sanitizes canaries, secret-like values, and protected path arguments.
- Command output redaction now delegates to `ProtectedContentPolicy`.
- RAG policy metadata/version checks were implemented; stale/missing-policy indexes rebuild before retrieval.

2026-05-20 backlog reconciliation:

- Focused privacy/search/retrieval tests passed again after the strict-audit side-path fixes:

```text
.\gradlew.bat test --tests "dev.talos.tools.impl.GrepToolTest" --tests "dev.talos.cli.repl.slash.WorkspaceCommandsTest*Grep" --tests "dev.talos.tools.impl.RetrieveToolTest" --tests "dev.talos.core.rag.RagDirtyIndexIntegrationTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" --no-daemon
```

- T326 closed the sensitive side-path parity gaps found by the strict audit.
- Private-document release claims remain forbidden until T295/T280/T285 evidence is complete.
