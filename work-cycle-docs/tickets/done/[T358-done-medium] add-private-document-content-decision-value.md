# [T358-done-medium] Add Private Document Content Decision Value

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T358`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T357-done-high] private-document-policy-decision-contract`

## Evidence Summary

- Source: post-T357 implementation after PR #22 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `b93b0550d4ec9469010dc3b7f3d5e6824341589d`.
- Beta push CI: run `#62`, `Beta Dev CI`, push event for `b93b0550`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T358`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - new neutral `dev.talos.core.privacy.DocumentContentDecision` value;
  - new `PrivateDocumentPolicy.decide(...)` adapter;
  - `ReadFileTool` now adapts a single private-document decision into
    `ToolContentMetadata`.
- Verification status: passed.

## Problem

T357 decided that the remaining private-document policy edges are not safe to
remove mechanically. The first implementation step is a preparatory decision
contract, not a baseline decrement.

Before T358, `ReadFileTool` assembled extracted-document metadata by calling
several independent `PrivateDocumentPolicy` methods:

- `privateDocumentContent(...)`;
- `rawArtifactPersistenceAllowed(...)`;
- `ragIndexAllowed(...)`;
- `decisionReason(...)`;

and it pulled `modelHandoffAllowed` from `DocumentExtractionResult`. That made
the tool boundary depend on a scattered set of privacy answers instead of one
explicit decision value.

## Change

T358 adds:

```text
dev.talos.core.privacy.DocumentContentDecision
```

Fields:

- `privateDocumentContent`;
- `modelHandoffAllowed`;
- `rawArtifactPersistenceAllowed`;
- `ragIndexAllowed`;
- `reason`.

The record is data only. It does not parse config, classify paths, read files,
prompt for approval, mutate privacy state, or import runtime/tools/CLI types.

T358 also adds:

```text
PrivateDocumentPolicy.decide(Config cfg,
                             DocumentExtractionRequest request,
                             FileCapabilityPolicy.FormatInfo info)
```

The method preserves existing behavior by delegating to the current runtime
policy methods and returning a single `DocumentContentDecision`.

`ReadFileTool` now calls `PrivateDocumentPolicy.decide(...)` once and adapts
that value into `ToolContentMetadata`.

## Non-Goals

- No baseline decrement.
- No relocation of `PrivateDocumentPolicy`.
- No removal of `DocumentExtractionResult.modelHandoffAllowed()`.
- No private-document indexing policy extraction.
- No RAG metadata change.
- No runtime approval prompt or trace behavior change.
- No `DocumentExtractionService` handoff redesign.

## Expected Architecture State

Architecture baseline remains `39`.

The remaining direct `PrivateDocumentPolicy` baseline rows still exist:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`

This is intentional. T358 makes the handoff decision explicit before later
private-document baseline reduction work.

## Tests Added

- `DocumentContentDecisionTest`
  - verifies the decision axes stay independent;
  - verifies null reasons normalize to an empty string.
- `PrivateDocumentPolicyTest`
  - verifies private-mode document decisions are bundled into a single value;
  - verifies developer-mode extracted document defaults are preserved.
- `ReadFileToolTest.extractedDocumentMetadataUsesSinglePrivateDocumentDecision`
  - verifies `ReadFileTool` uses `PrivateDocumentPolicy.decide(...)` instead
    of assembling metadata from separate private-document policy calls.

## Verification

- RED focused test run: failed as expected because
  `DocumentContentDecision` and `PrivateDocumentPolicy.decide(...)` did not
  exist.
- GREEN focused test run:
  `.\gradlew.bat test --tests "dev.talos.core.privacy.DocumentContentDecisionTest" --tests "dev.talos.runtime.policy.PrivateDocumentPolicyTest" --tests "dev.talos.tools.impl.ReadFileToolTest.extractedDocumentMetadataUsesSinglePrivateDocumentDecision" --no-daemon`:
  passed.
- Focused private-document regression suite:
  `.\gradlew.bat test --tests "dev.talos.core.privacy.DocumentContentDecisionTest" --tests "dev.talos.runtime.policy.PrivateDocumentPolicyTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.core.index.IndexerPrivateDocumentPolicyTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --no-daemon`:
  passed.
- `git diff --check`: passed, line-ending warnings only.
- `.\gradlew.bat validateReleaseLedger validateArchitectureBoundaries --no-daemon`:
  passed.
- `.\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

T359 should not delete an edge casually. The next correct implementation is
the narrow private-document indexing policy described by T357:

```text
dev.talos.core.privacy.PrivateDocumentIndexingPolicy
```

It should migrate `Indexer` only if validation proves the resulting
`Indexer -> PrivateDocumentPolicy` baseline row is stale. That ticket should
not touch `DocumentExtractionService` handoff ownership.

Confidence: high.
