# [T368-done-medium] Move Context Ledger Primitives To Core Context

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T368`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T367-done-medium] move-document-extraction-service-to-core-content-policy`

## Evidence Summary

- Source: post-T367 implementation after PR #32 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `56ee545a548cbac58f9007f05d9fa81446bfdcbe`.
- Beta push CI: run `#92`, `Beta Dev CI`, push event for `56ee545a`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T368`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Verification status: passed locally before commit.

## Problem

`RagService` is core RAG/retrieval code, but it imported runtime context-ledger
evidence primitives:

```text
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextDecision
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItem
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItemSource
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextLedgerCapture
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ExecutionBoundary
```

The ledger is evidence infrastructure shared by RAG, runtime tool execution,
trace capture, and prompt-debug inspection. It is not runtime-only behavior.

## Change

T368 moves the context-ledger primitives from:

```text
dev.talos.runtime.context
```

to:

```text
dev.talos.core.context
```

Moved types:

- `ContextDecision`
- `ContextItem`
- `ContextItemSource`
- `ContextLedger`
- `ContextLedgerCapture`
- `ContextLedgerSnapshot`
- `ContextLedgerSummary`
- `ExecutionBoundary`

Runtime-only active-task/artifact context types remain in
`dev.talos.runtime.context`.

`ContextItem` now uses the neutral `ProtectedPathTokens` safety primitive for
protected path hints instead of the runtime protected-content facade.

## Baseline Result

Architecture baseline moved:

```text
17 -> 12
```

Removed entries:

```text
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextDecision
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItem
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItemSource
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextLedgerCapture
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ExecutionBoundary
```

## Verification

- RED architecture ratchet:
  `.\\gradlew.bat validateArchitectureBoundaries --no-daemon` failed as
  expected with the five removed `RagService -> runtime.context` rows.
- RED ownership test:
  `.\\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest.ragServiceUsesCoreContextLedgerOwnership" --no-daemon`
  failed before implementation because `RagService` still imported runtime
  context-ledger types.
- Focused GREEN test run:
  `.\\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest" --tests "dev.talos.core.context.ContextLedgerTest" --tests "dev.talos.core.context.ContextItemProtectedPathParityTest" --tests "dev.talos.core.context.ContextLedgerArtifactScanTest" --tests "dev.talos.cli.prompt.PromptDebugInspectorContextLedgerTest" --tests "dev.talos.runtime.trace.LocalTurnTraceContextLedgerTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --no-daemon`
  passed.
- `.\\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- Final verification before commit:
  `git diff --check` and `.\\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

After T368, inspect the remaining `12` baseline entries before choosing T369.
The remaining debt is no longer a cheap safety-policy burn-down. Likely tracks:

- `RagService -> ToolCallParser` defensive stripping ownership;
- runtime-to-CLI `Context`, `ModeController`, and `SessionMemory` coupling;
- SPI purity around `Config`, `EngineRuntimeConfig`, and `ChunkMetadata`.

Confidence: high.
