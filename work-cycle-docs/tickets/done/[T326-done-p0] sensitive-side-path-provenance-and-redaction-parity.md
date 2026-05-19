# T326 - Sensitive Side-Path Provenance And Redaction Parity

Severity: P0 risk / High

Status: Done

Source: Five scenario big audit and Agent 5 static audit, 2026-05-19

## Problem

The direct `.env` and private-document `read_file` paths are much stronger than before, but sensitive data side paths do not yet share one authoritative privacy boundary.

The highest-risk side paths are:

- prompt-debug/provider-body redaction using narrower path heuristics than `ProtectedPathPolicy`;
- `talos.grep` over extracted PDF/DOCX/XLS/XLSX returning raw extracted private lines without `ToolContentMetadata`/`PrivateDocumentPolicy`;
- API indexing bypass through direct `Indexer` access;
- normal health/bank/tax `.md`, `.txt`, and `.csv` files not being private by provenance.

## Evidence

Sensitive live audit direct path:

```text
local/manual-testing/five-scenario-audit-20260519-221645/20260519-222015/five-sensitive-data-boundary.txt
```

Artifact scan:

```text
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local\manual-testing\five-scenario-audit-20260519-221645,local\manual-workspaces\five-scenario-audit-20260519-221645" --no-daemon
```

Result: passed for configured canaries.

Static audit found:

- `PromptDebugInspector` has local protected-path heuristics instead of full `ProtectedPathPolicy` parity.
- `TraceRedactor` and context ledger path redaction also have narrower path logic.
- `GrepTool` document extraction path lacks private-document handoff metadata.
- `TalosKnowledgeEngine.index()` can bypass the same private-mode guard as `RagService.reindex()`.

## Expected Behavior

Every path protected by `ProtectedPathPolicy` must be treated as protected across:

- tool execution,
- prompt-debug,
- provider body,
- session store,
- trace,
- context ledger,
- artifact scanner.

Private-mode extracted document text must not leak through grep/retrieve/index side paths.

## Regression Tests

Add:

```text
PromptDebugInspectorProtectedPathParityTest
GrepToolPrivateDocumentPolicyTest
TraceRedactorProtectedPathParityTest
TalosKnowledgeEnginePrivacyTest
ContextItemProtectedPathParityTest
SensitivePrivateModeTextFilePolicyTest
```

## Fix Direction

1. Introduce or reuse a shared protected-path redaction helper backed by `ProtectedPathPolicy`.
2. Patch `GrepTool.searchExtractedFile()` to enforce `PrivateDocumentPolicy` in private mode before returning extracted document matches.
3. Route `TalosKnowledgeEngine.index()` through `RagService.reindex()` or the same private-mode guard.
4. Make `/privacy help` explicit that private mode protects policy classes, not arbitrary personal facts in normal text unless a future private-folder policy is enabled.

## Resolution

Implemented 2026-05-20.

- `ProtectedPathPolicy.looksLikeProtectedPathToken(...)` is now the shared protected path token classifier used by prompt-debug/provider-body redaction, log/tool-parameter path sanitization, trace path hints, and context item path hints.
- `GrepTool` and slash `/grep` now withhold extracted private-document match lines in private mode when extraction metadata says model handoff is not allowed.
- `TalosKnowledgeEngine.index(...)` now routes through `RagService.reindex(...)` instead of direct `Indexer` access, preserving the private-mode RAG disabled guard.
- `/privacy help` now states that ordinary personal facts in normal `.md/.txt/.csv` files are not private by provenance unless path/content protected-policy signals match.

Evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.impl.GrepToolTest" --tests "dev.talos.cli.repl.slash.WorkspaceCommandsTest*Grep" --tests "dev.talos.api.TalosKnowledgeEnginePrivacyTest" --tests "dev.talos.core.index.IndexerPrivateDocumentPolicyTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --tests "dev.talos.runtime.policy.ProtectedPathPolicyTest" --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.trace.TraceRedactorTest" --tests "dev.talos.runtime.trace.LocalTurnTraceTest" --tests "dev.talos.runtime.context.ContextItemProtectedPathParityTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PrivacyCommandTest.private_mode_help_explains_model_context_and_artifacts" --no-daemon
```
