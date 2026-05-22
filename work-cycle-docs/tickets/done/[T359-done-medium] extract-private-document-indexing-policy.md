# [T359-done-medium] Extract Private Document Indexing Policy

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T359`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T358-done-medium] add-private-document-content-decision-value`

## Evidence Summary

- Source: post-T358 implementation after PR #23 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `c9905d453ee822147a3135b8e134f6fff5ccd227`.
- Beta push CI: run `#65`, `Beta Dev CI`, push event for `c9905d45`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T359`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - new core-owned `dev.talos.core.privacy.PrivateDocumentIndexingPolicy`;
  - `Indexer` now depends on the core indexing policy instead of runtime
    `PrivateDocumentPolicy`;
  - runtime `PrivateDocumentPolicy.ragIndexAllowed(...)` and
    `decisionReason(...)` delegate to the core indexing policy to preserve
    behavior;
  - architecture baseline reduced by one stale entry.
- Verification status: passed.

## Problem

After T358, `ReadFileTool` consumed one explicit private-document content
decision, but core indexing still imported runtime `PrivateDocumentPolicy`
only to decide whether extracted document text may enter the RAG index.

That edge was no longer justified:

```text
core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy
```

`Indexer` does not need model-handoff approval notes, raw artifact persistence,
tool-result metadata, CLI privacy status text, or runtime approval behavior. It
needs one narrow decision:

```text
Given cfg, workspace root, document path, extraction intent INDEX, and format
info, may this extracted document text be indexed?
```

## Change

T359 adds:

```text
dev.talos.core.privacy.PrivateDocumentIndexingPolicy
```

Responsibilities:

- block null requests from indexing;
- block direct protected workspace paths through
  `ProtectedWorkspacePaths.isProtectedPath(...)`;
- in private mode, allow extracted-document indexing only when both:
  - private-mode RAG is enabled; and
  - `privacy.document_extraction.allow_rag_indexing` is enabled;
- preserve existing decision reason strings.

Allowed dependencies:

- `Config`;
- `CfgUtil`;
- `PrivacyConfigFacts`;
- `DocumentExtractionRequest`;
- `FileCapabilityPolicy.FormatInfo`;
- `ProtectedWorkspacePaths`.

Forbidden dependencies:

- runtime policy;
- tools metadata;
- CLI status text;
- approval gates;
- trace capture;
- command execution;
- RAG context ledger records.

`Indexer` now calls:

```text
PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(...)
PrivateDocumentIndexingPolicy.decisionReason(...)
```

Runtime `PrivateDocumentPolicy` delegates its RAG indexing decision and shared
reason string to the new core policy, preserving existing runtime/tool
metadata behavior while removing the lower-layer dependency.

## Baseline Result

Architecture baseline moved:

```text
39 -> 38
```

Removed entry:

```text
core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy
```

Remaining direct `PrivateDocumentPolicy` baseline rows:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`

Those are deliberately untouched. `DocumentExtractionService` handoff
ownership is still the higher-risk transition and must not be folded into this
ticket.

## Tests Added

- `PrivateDocumentIndexingPolicyTest`
  - verifies private-mode extracted document indexing requires both
    private-mode RAG and document-extraction RAG opt-in;
  - verifies developer-mode extracted document indexing remains allowed;
  - verifies protected workspace paths are never indexable;
  - verifies null requests are not indexable.
- `IndexerPrivateDocumentPolicyTest.indexerUsesCorePrivateDocumentIndexingPolicyInsteadOfRuntimePolicy`
  - verifies `Indexer` imports the core policy;
  - verifies `Indexer` no longer imports runtime `PrivateDocumentPolicy`;
  - verifies the removed baseline row stays removed.

## Verification

- RED focused test run:
  `.\gradlew.bat test --tests "dev.talos.core.privacy.PrivateDocumentIndexingPolicyTest" --tests "dev.talos.core.index.IndexerPrivateDocumentPolicyTest.indexerUsesCorePrivateDocumentIndexingPolicyInsteadOfRuntimePolicy" --no-daemon`:
  failed as expected because `PrivateDocumentIndexingPolicy` did not exist.
- GREEN focused test run:
  `.\gradlew.bat test --tests "dev.talos.core.privacy.PrivateDocumentIndexingPolicyTest" --tests "dev.talos.core.index.IndexerPrivateDocumentPolicyTest.indexerUsesCorePrivateDocumentIndexingPolicyInsteadOfRuntimePolicy" --no-daemon`:
  passed.
- Focused private-document indexing/runtime suite:
  `.\gradlew.bat test --tests "dev.talos.core.privacy.PrivateDocumentIndexingPolicyTest" --tests "dev.talos.core.index.IndexerPrivateDocumentPolicyTest" --tests "dev.talos.runtime.policy.PrivateDocumentPolicyTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --no-daemon`:
  passed.
- `git diff --check`: passed, line-ending warnings only.
- `.\gradlew.bat validateReleaseLedger validateArchitectureBoundaries --no-daemon`:
  passed.
- `.\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

Do not attack `DocumentExtractionService -> PrivateDocumentPolicy` yet unless
the handoff transition is explicitly designed and tested. The next step should
inspect the remaining `38` baseline entries and decide whether another
low-risk policy split exists, or whether the architecture-ratchet sequence
should pause for a broader extraction handoff design.

Confidence: high.
