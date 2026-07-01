# [T345-done-high] Policy And Sink Safety Ownership Decision

Status: done
Priority: high
Date: 2026-05-21
Branch: `T345`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T344-done-medium] remove-tool-registry-runtime-log-policy-edge`

## Evidence Summary

- Source: post-T344 architecture decision request after PR #9 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T345`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `dfc71b63cf1a5b8d6a2636c3396f47a2c28a057f`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: documentation-only architecture decision ticket.
- Verification status: documentation hygiene, architecture validation, and
  release ledger validation passed.

## Problem

The architecture ratchet has correctly reduced the baseline from the original
packet, but the next decision cannot be made by picking the smallest remaining
line in `config/architecture-boundary-baseline.txt`.

The remaining `SafeLogFormatter` edges expose a deeper ownership problem:

- `SafeLogFormatter` is packaged under `dev.talos.runtime.policy`.
- Its actual responsibility is sink-safe rendering for logs and diagnostics.
- It is used by `core`, `engine`, and `tools` code paths.
- It delegates to `ProtectedContentPolicy`.
- `ProtectedContentPolicy` is also not cleanly runtime-only:
  - it owns pure text redaction primitives;
  - it owns protected-path token checks through `ProtectedPathPolicy`;
  - it owns tool-result sanitization adapters through `ToolResult` and
    `ToolError`;
  - it is used by core extraction, core indexing, core RAG, tools, runtime,
    CLI prompt-debug inspection, trace redaction, session persistence, and
    command output handling.

Therefore deleting one more `SafeLogFormatter` call site would improve the
counter while preserving the architectural lie. The right next move is to
decide ownership and only then continue burn-down.

## Decision

T345 decides the target ownership model for sink safety and protected-content
policy.

### 1. Sink-safe formatting belongs in a neutral lower layer

`SafeLogFormatter` must not remain under `dev.talos.runtime.policy`.

Its correct owner is a neutral safety package that lower and upper layers can
use without importing runtime orchestration policy. The target package should
be a new top-level package:

```text
dev.talos.safety
```

Reason:

- `dev.talos.core` is not neutral enough. It already contains config, indexing,
  LLM, RAG, extraction, and prompt-facing behavior.
- `dev.talos.engine` and `dev.talos.tools` already import selected core types,
  but putting sink safety in core would make core a larger utility bucket.
- A top-level `dev.talos.safety` package can be made stricter than core: no
  imports from `dev.talos.core`, `dev.talos.runtime`, `dev.talos.tools`,
  `dev.talos.engine`, `dev.talos.cli`, or `dev.talos.app`.
- Sink safety is cross-cutting infrastructure, not runtime policy execution.

Target invariant:

```text
dev.talos.safety -> JDK only, plus possibly stable third-party primitives if
ever needed. It must not import Talos upper-layer packages.
```

### 2. Pure protected-content redaction must be split from runtime policy

The pure sanitizer primitives currently inside `ProtectedContentPolicy` should
move to `dev.talos.safety`.

Target neutral primitives:

- canary redaction;
- private document fact canary redaction;
- secret-like assignment redaction;
- private marker assignment redaction;
- generic text sanitization for sink output;
- map/parameter value sanitization;
- protected-path token recognition for path-like strings;
- sink-safe throwable message rendering.

These functions do not need:

- `Config`;
- approval state;
- `ToolCall`;
- `ToolResult`;
- `ToolError`;
- workspace paths;
- runtime trace state;
- CLI context.

### 3. Tool-result sanitization is an adapter, not a primitive

`ProtectedContentPolicy.sanitizeToolResult(ToolResult)` is not a lower-layer
primitive because it imports `dev.talos.tools.ToolResult` and `ToolError`.

Target ownership:

```text
Runtime/tool execution adapter owns ToolResult sanitization.
Neutral safety owns only text/map redaction primitives.
```

Possible future class names:

- `dev.talos.runtime.policy.ToolResultRedactionPolicy`
- or `dev.talos.runtime.toolcall.ToolResultSanitizer`

The exact name can be chosen in the implementation ticket, but the adapter must
not be moved into `dev.talos.safety`.

### 4. Workspace protected-path classification remains runtime policy for now

`ProtectedPathPolicy` is not a pure text sanitizer. It currently depends on:

- `ToolCall`;
- `ToolAliasPolicy`;
- `PathArgumentCanonicalizer`;
- `WorkspaceBatchPlanParser`;
- workspace-relative path resolution;
- mutation/resource decision records.

Target ownership:

```text
dev.talos.runtime.policy.ProtectedPathPolicy remains runtime policy until the
tool/workspace plan boundary is redesigned.
```

However, its protected-token recognizer should be extracted into
`dev.talos.safety` so sink-safe logging can redact path-looking tokens without
importing runtime policy.

Target split:

- `dev.talos.safety.ProtectedPathTokens`:
  pure string/token recognition such as `.env`, `.ssh`, `secrets/`,
  `credentials`, private-key filenames, `.github/workflows`, `.git`, `.gnupg`.
- `dev.talos.runtime.policy.ProtectedPathPolicy`:
  workspace-aware and tool-call-aware resource classification.

### 5. Protected-read scope remains runtime/config policy until inverted

`ProtectedReadScopePolicy` is config-backed behavior for private mode,
approved protected-read handoff, raw artifact persistence, and RAG enablement.
It currently leaks into core RAG/indexing and CLI slash commands because core
components ask runtime policy questions directly.

Target ownership:

```text
Runtime owns approval-scope and private-mode enforcement.
Core code should eventually receive privacy decisions through a narrow
interface or a core-owned config view instead of importing runtime policy.
```

Do not move `ProtectedReadScopePolicy` wholesale into core. That would move
runtime approval semantics into the lower layer.

### 6. Private document policy is mixed and must be split later

`PrivateDocumentPolicy` combines:

- document extraction format facts from core ingestion/extraction;
- protected path status;
- private-mode config;
- model handoff policy;
- raw artifact persistence policy;
- RAG indexing policy;
- user-facing decision reason strings.

Target ownership:

- document-format facts belong with core extraction/ingest;
- privacy-mode and handoff decisions belong to runtime policy;
- core extraction/indexing should use a narrow decision interface or value
  object instead of importing runtime policy directly;
- user-facing privacy notes should stay near runtime/CLI policy, not inside
  low-level extraction.

Do not move `PrivateDocumentPolicy` wholesale. It is a mixed class and must be
decomposed.

## Rejected Options

### Rejected: continue deleting single `SafeLogFormatter` call sites

This improves the metric while leaving the wrong package owner in place.
It also silently changes diagnostics from redacted detail to no detail, even
where redacted detail may still be useful.

### Rejected: move `SafeLogFormatter` into `dev.talos.core.util`

`core.util.Sanitize` already owns prompt/terminal/control-character sanitation.
Sink-safe redaction for logs and durable artifacts is a different boundary.
Putting it in `core.util` would turn core into a miscellaneous utility layer
and would not make the sink-safety invariant explicit.

### Rejected: move all of `ProtectedContentPolicy` to core or safety

`ProtectedContentPolicy` currently imports `ToolResult` and `ToolError` and
delegates to workspace/tool-call policy. Moving it wholesale would drag tool
and runtime policy concepts into a lower layer.

### Rejected: introduce a DI framework

The problem is ownership and dependency direction, not object construction.
A DI container would make the dependency graph more abstract without making it
more correct.

## Remaining Baseline Classification

Current baseline count after T344:

- Total: `56`
- `core-no-runtime`: `17`
- `engine-no-runtime`: `2`
- `runtime-core-no-cli`: `15`
- `spi-no-upper-layers`: `4`
- `tools-no-runtime`: `18`

### Package relocation: neutral sink safety

These should be handled by extracting neutral safety primitives and moving
`SafeLogFormatter` ownership, not by deleting call sites:

- `core-no-runtime|src/main/java/dev/talos/core/embed/EmbeddingsClient.java|dev.talos.runtime.policy.SafeLogFormatter`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.SafeLogFormatter`
- `core-no-runtime|src/main/java/dev/talos/core/index/LuceneStore.java|dev.talos.runtime.policy.SafeLogFormatter`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.SafeLogFormatter`
- `engine-no-runtime|src/main/java/dev/talos/engine/compat/CompatChatClient.java|dev.talos.runtime.policy.SafeLogFormatter`
- `engine-no-runtime|src/main/java/dev/talos/engine/ollama/OllamaChatClient.java|dev.talos.runtime.policy.SafeLogFormatter`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ContentVerifier.java|dev.talos.runtime.policy.SafeLogFormatter`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/FileEditTool.java|dev.talos.runtime.policy.SafeLogFormatter`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/FileWriteTool.java|dev.talos.runtime.policy.SafeLogFormatter`

Expected implementation class:

```text
T346 - Extract neutral sink safety primitives and SafeLogFormatter
```

### Split or invert: protected-content and private-document policy

These should not be solved by moving one class wholesale:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionPreflight.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RetrieveTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`

Correct direction:

- pure text/path-token redaction moves to `dev.talos.safety`;
- tool-result adapters stay runtime/toolcall;
- private document handoff and raw artifact policy stay runtime until an
  explicit interface/value object is introduced;
- core extraction/indexing/RAG should not ask runtime classes directly.

### Contract relocation or interface inversion: RAG/runtime context

These are not sink-safety work:

- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.ToolCallParser`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextDecision`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItem`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItemSource`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextLedgerCapture`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ExecutionBoundary`

Correct direction:

- either move context result contracts to a lower package;
- or make `RagService` return core-owned retrieval/context results and let
  runtime adapt them into runtime context ledger records.

### Separate design: runtime-to-CLI session boundary

These remain a separate architecture decision:

- `runtime-core-no-cli|src/main/java/dev/talos/core/context/ConversationManager.java|dev.talos.cli.repl.SessionMemory`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/ActiveTaskContextUpdateListener.java|dev.talos.cli.repl.SessionMemory`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/CliApprovalGate.java|dev.talos.cli.ui.ApprovalPromptRenderer`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/CliApprovalGate.java|dev.talos.cli.ui.CliTheme`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/JsonTurnLogAppender.java|dev.talos.cli.repl.Result`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/MemoryUpdateListener.java|dev.talos.cli.repl.Result`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/MemoryUpdateListener.java|dev.talos.cli.repl.SessionMemory`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/Session.java|dev.talos.cli.repl.SessionMemory`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/ToolCallLoop.java|dev.talos.cli.repl.Context`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnProcessor.java|dev.talos.cli.modes.ModeController`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnProcessor.java|dev.talos.cli.repl.Context`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnProcessor.java|dev.talos.cli.repl.Result`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/TurnResult.java|dev.talos.cli.repl.Result`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/context/ActiveTaskContextUpdater.java|dev.talos.cli.repl.Result`
- `runtime-core-no-cli|src/main/java/dev/talos/runtime/toolcall/LoopState.java|dev.talos.cli.repl.Context`

Correct direction:

- introduce runtime-owned turn input/output/session contracts;
- keep CLI rendering and REPL memory as adapters;
- avoid moving CLI classes downward.

### Separate design: tool/runtime command and workspace contracts

These should be addressed by command/workspace contract ownership, not by
sink-safety work:

- `tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchOperation`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchPlan`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchPlanParser`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandPlan`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandPlanRejectedException`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandProfileRegistry`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandResult`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandRunner`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandToolPlanner`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.ProcessCommandRunner`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.trace.LocalTurnTraceCapture`

Correct direction:

- either move command/workspace execution contracts below tools;
- or make these runtime-owned tools invoked through runtime execution stages;
- do not duplicate command policy inside tools.

### Separate design: SPI purity

These need SPI boundary cleanup:

- `spi-no-upper-layers|src/main/java/dev/talos/spi/CorpusStore.java|dev.talos.core.ingest.ChunkMetadata`
- `spi-no-upper-layers|src/main/java/dev/talos/spi/EngineRegistry.java|dev.talos.core.Config`
- `spi-no-upper-layers|src/main/java/dev/talos/spi/EngineRegistry.java|dev.talos.core.EngineRuntimeConfig`
- `spi-no-upper-layers|src/main/java/dev/talos/spi/ModelEngineProvider.java|dev.talos.core.Config`

Correct direction:

- make SPI expose SPI-owned value objects;
- keep `core.Config` out of SPI contracts over time.

## T346 Implementation Plan

T346 should be the next implementation ticket.

Goal:

```text
Extract neutral sink-safety primitives and move SafeLogFormatter out of
dev.talos.runtime.policy without changing runtime behavior.
```

Expected files:

- Create `src/main/java/dev/talos/safety/ProtectedContentSanitizer.java`
- Create `src/main/java/dev/talos/safety/ProtectedPathTokens.java`
- Create or move `src/main/java/dev/talos/safety/SafeLogFormatter.java`
- Modify `src/main/java/dev/talos/runtime/policy/ProtectedContentPolicy.java`
- Modify `src/main/java/dev/talos/runtime/policy/ProtectedPathPolicy.java`
- Update imports currently pointing at
  `dev.talos.runtime.policy.SafeLogFormatter`
- Update `src/test/java/dev/talos/runtime/policy/SensitiveLogRedactionTest.java`
- Add architecture coverage that `dev.talos.safety` does not import Talos
  upper-layer packages.
- Remove the nine stale `SafeLogFormatter` baseline entries only after
  `validateArchitectureBoundaries` proves they are stale.

Expected test shape:

- RED test: `SafeLogFormatter` is not in `dev.talos.runtime.policy` for lower
  layer call sites and `dev.talos.safety` imports no Talos packages.
- GREEN implementation: move pure sanitizer code and update imports.
- Focused tests:
  - `SensitiveLogRedactionTest`
  - `RuntimeSinkSafetyInventoryTest`
  - `ArchitectureBoundaryValidationTaskTest` if the scanner rule changes
- Architecture scanner:
  - `validateArchitectureBoundaries`
- Full gate:
  - `.\gradlew.bat check --no-daemon`

Expected baseline result if T346 is scoped correctly:

```text
56 -> 47
```

That is not the reason to do T346. The reason is that sink safety gets the
correct owner. The counter reduction is a consequence.

## Acceptance Criteria

- T345 records a source-backed decision for sink-safety ownership.
- T345 answers whether sink-safe logging should be neutral lower-layer
  infrastructure.
- T345 decides how to split pure sanitizer primitives, tool-result adapters,
  runtime/private-mode policy, and protected path classification.
- T345 classifies the remaining baseline by ownership move type.
- T345 names the next implementation ticket.
- T345 does not change production behavior.
- `validateArchitectureBoundaries` passes.
- `validateReleaseLedger` passes.
- `git diff --check` passes, allowing repository line-ending warnings only.
- No generated audit artifacts are committed.

## Verification

Diff hygiene:

```powershell
git diff --check
```

Result: passed.

Architecture and release ledger validation:

```powershell
.\gradlew.bat validateArchitectureBoundaries validateReleaseLedger --no-daemon
```

Result: passed.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- A new `dev.talos.safety` package needs an architecture rule immediately.
  Otherwise it can become a second utility dump.
- Moving only `SafeLogFormatter` without extracting path-token and text
  sanitizer primitives would simply move the dependency cycle.
- Moving all private-document or protected-read policy downward would weaken
  ownership by making lower layers own runtime approval semantics.

## Known Follow-Ups

- T346: extract neutral sink-safety primitives and `SafeLogFormatter`.
- Follow-up: split `ProtectedContentPolicy.sanitizeToolResult` into a runtime
  tool-result adapter.
- Follow-up: design core/runtime privacy decision interfaces for extraction,
  indexing, and RAG.
- Follow-up: runtime-to-CLI session contract split.
- Follow-up: command/workspace tool ownership decision.
