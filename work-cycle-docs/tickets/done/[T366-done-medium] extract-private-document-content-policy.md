# [T366-done-medium] Extract Private Document Content Policy

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T366`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T365-done-medium] move-batch-workspace-apply-tool-to-runtime-workspace`

## Evidence Summary

- Source: post-T365 implementation after PR #30 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `a3f03e0a9768fc41c7f0ab829fd7d29baafb1f6b`.
- Beta push CI: run `#86`, `Beta Dev CI`, push event for `a3f03e0`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T366`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Verification status: passed locally before commit.

## Problem

`ReadFileTool` lived in `dev.talos.tools.impl` but imported runtime privacy
policy only to compute extracted-document content metadata:

```text
tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy
```

That was the wrong direction. The tool needs a content decision for document
metadata, not a runtime policy facade. The mixed runtime facade remains needed
for current extraction-service handoff behavior, but the pure content decision
can be owned by core privacy.

## Change

T366 adds:

```text
dev.talos.core.privacy.PrivateDocumentContentPolicy
```

The new core policy owns private extracted-document content decisions:

- whether extracted content is private document content;
- whether model handoff is allowed;
- whether raw artifact persistence is allowed;
- whether RAG indexing is allowed;
- the decision reason.

`PrivateDocumentPolicy` remains as the runtime facade and delegates content
decisions to the core policy. `ReadFileTool` now calls the core policy directly.

## Baseline Result

Architecture baseline moved:

```text
19 -> 18
```

Removed entry:

```text
tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy
```

## Guardrails

T366 intentionally did not move:

- `DocumentExtractionService -> PrivateDocumentPolicy`;
- `RagService` runtime context ledger dependencies;
- runtime-to-CLI session/context edges;
- SPI purity edges.

Those are separate ownership decisions and should not be hidden inside this
content-policy extraction.

## Verification

- RED architecture ratchet:
  `.\\gradlew.bat validateArchitectureBoundaries --no-daemon` failed as
  expected with the single removed `ReadFileTool -> PrivateDocumentPolicy`
  baseline row.
- RED test:
  `.\\gradlew.bat test --tests "dev.talos.core.privacy.PrivateDocumentContentPolicyTest" --tests "dev.talos.tools.impl.ReadFileToolTest.extractedDocumentMetadataUsesSinglePrivateDocumentDecision" --no-daemon`
  failed before implementation because `PrivateDocumentContentPolicy` did not
  exist.
- Focused GREEN test run:
  `.\\gradlew.bat test --tests "dev.talos.core.privacy.PrivateDocumentContentPolicyTest" --tests "dev.talos.runtime.policy.PrivateDocumentPolicyTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --no-daemon`
  passed.
- `.\\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- Final verification before commit:
  `git diff --check` and `.\\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

After T366, inspect the remaining `18` baseline entries before choosing T367.
Do not jump directly at `DocumentExtractionService -> PrivateDocumentPolicy`
unless source inspection proves the remaining runtime facade dependency can be
removed without changing extraction handoff behavior.

Confidence: high.
