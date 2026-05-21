# [T349-done-high] Protected Path And Private Document Policy Boundary Decision

Status: done
Priority: high
Date: 2026-05-21
Branch: `T349`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T348-done-medium] move-document-extraction-service-sanitizer-to-safety`

## Evidence Summary

- Source: post-T348 architecture continuation after PR #13 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Base branch: `origin/v0.9.0-beta-dev` at
  `620c55dae573434e9d6af37ed26d335c1bcf9d51`.
- Beta push CI: run `#35`, `Beta Dev CI`, push event for `620c55da`,
  completed successfully.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: documentation-only architecture decision ticket.
- Verification status: documentation hygiene, architecture validation, and
  release ledger validation passed.

## Problem

T346, T347, and T348 removed the cheap ownership lie around pure sink-safety
redaction. `dev.talos.safety.ProtectedContentSanitizer` now owns pure text
redaction, and lower layers no longer need runtime policy merely to sanitize
document extraction output.

The remaining policy edges are different. They are not cheap sanitizer moves.
They combine:

- workspace protected-path classification;
- tool-call path extraction;
- private-mode defaults;
- approved protected-read scope;
- RAG indexing permission;
- document extraction handoff decisions;
- index metadata invalidation;
- user-facing privacy notes;
- tool-result adapters.

Moving any of `ProtectedContentPolicy`, `PrivateDocumentPolicy`, or
`ProtectedReadScopePolicy` wholesale would make the architecture worse. It
would move runtime approval/private-mode semantics into lower packages instead
of splitting the responsibilities.

## Current Baseline Shape

After T348, the architecture baseline has `45` entries:

- `core-no-runtime`: `11`
- `runtime-core-no-cli`: `15`
- `spi-no-upper-layers`: `4`
- `tools-no-runtime`: `15`

The remaining policy-specific edges are:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RetrieveTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`

## Source Findings

`ProtectedContentPolicy` is now a mixed runtime adapter:

- pure text redaction delegates to `ProtectedContentSanitizer`;
- protected token recognition delegates to `ProtectedPathTokens`;
- direct workspace path checks delegate through runtime `ProtectedPathPolicy`;
- tool-result sanitization imports `ToolResult` and `ToolError`;
- protected-content note rendering is user-facing text.

`ProtectedPathPolicy` is also mixed:

- direct workspace path classification is a local safety primitive;
- tool-call path extraction depends on `ToolCall`, `ToolAliasPolicy`,
  `WorkspaceBatchPlanParser`, and `PathArgumentCanonicalizer`;
- runtime approval/resource decisions depend on `ResourceDecision`.

`PrivateDocumentPolicy` is mixed:

- document-format facts come from core extraction/ingest;
- protected-path status comes from runtime protected-content policy;
- private-mode and RAG flags come from `ProtectedReadScopePolicy`;
- model handoff, raw artifact persistence, RAG indexing, and user-facing
  decision reasons are runtime/privacy decisions.

`ProtectedReadScopePolicy` is mixed:

- private-mode config parsing is a lower-level config fact;
- approved protected-read model handoff and raw artifact persistence are
  runtime policy;
- `/privacy` state mutation and user-facing notes are CLI/runtime behavior;
- RAG enablement in private mode affects core indexing and retrieval.

## Decision

### 1. Direct workspace protected-path classification must split below runtime

The direct question:

```text
Given a workspace root and a concrete path, is this path protected?
```

is not runtime orchestration. It is local safety infrastructure. Core indexing,
core RAG, and retrieval/search tools all need this answer without importing
runtime policy.

Target owner:

```text
dev.talos.safety.ProtectedWorkspacePaths
```

Target responsibilities:

- normalize workspace and candidate paths;
- reject workspace escapes;
- derive the workspace-relative path;
- classify protected path kind through `ProtectedPathTokens`;
- expose a simple `isProtectedPath(Path workspace, Path path)` helper;
- expose a small JDK-only decision record if implementation needs detail.

Forbidden dependencies:

- no `Config`;
- no `ToolCall`;
- no `ToolResult`;
- no `ToolError`;
- no runtime, core, tools, CLI, engine, SPI, or app imports.

Runtime `ProtectedPathPolicy` remains the owner of tool-call resource
classification. It should delegate direct path classification to the lower
safety primitive and continue adapting `ToolCall` inputs into runtime
`ResourceDecision` records.

### 2. `ProtectedContentPolicy` must remain runtime-facing adapter code

Do not move `ProtectedContentPolicy` wholesale. Its name is now too broad, but
the class still owns runtime-facing adapter behavior:

- `sanitizeToolResult(ToolResult)`;
- backward-compatible runtime redaction facade methods;
- protected-content note wording used by runtime/tool output;
- integration with runtime protected path policy until call sites migrate.

Lower layers should stop importing it. They should use:

- `ProtectedContentSanitizer` for text/search-line redaction;
- `ProtectedWorkspacePaths` for direct path checks;
- local or lower-level notice helpers only when the notice is not runtime
  approval wording.

### 3. `PrivateDocumentPolicy` must be split by decision type, not moved

`PrivateDocumentPolicy` must not be moved into core as a whole.

Target split:

- Core extraction owns document extraction facts:
  - whether a file is extractable text;
  - extraction intent;
  - extraction result status;
  - safe extracted text;
  - extraction provenance.
- Lower safety owns direct protected-path classification.
- Runtime privacy owns whether extracted document text may be:
  - sent to model context;
  - persisted raw;
  - indexed in RAG;
  - described with a user-facing reason.

Target future shape:

```text
core.extract.DocumentExtractionService:
  extracts and sanitizes local document text, but does not decide runtime
  model-handoff scope.

runtime.policy.PrivateDocumentPolicy or successor:
  computes a DocumentContentDecision for tool/runtime handoff after extraction.

tools/runtime adapters:
  attach ToolContentMetadata using the runtime decision, not by making core
  extraction import runtime policy.
```

Possible future value object:

```text
DocumentContentDecision(
    privateDocumentContent,
    modelHandoffAllowed,
    rawArtifactPersistenceAllowed,
    ragIndexAllowed,
    reason
)
```

The value object may live in `dev.talos.tools` or a lower contract package if
tool metadata needs it. The policy that computes it should remain runtime
until private-mode and approval semantics are split further.

### 4. `ProtectedReadScopePolicy` must split config facts from approval scope

Do not move `ProtectedReadScopePolicy` wholesale into core.

Target split:

- Lower-level privacy config facts:
  - private/developer mode;
  - whether RAG is enabled in private mode.
- Runtime approval scope:
  - approved protected-read default scope;
  - allow-send-to-model override;
  - raw artifact persistence;
  - user-facing approval notes;
  - `/privacy` mutation behavior.

Core RAG and indexing should eventually depend only on lower-level privacy
config facts or on an injected policy decision. They should not import runtime
approval-scope policy.

### 5. Index metadata must stop depending on mixed runtime policy versions

`Indexer` currently uses `ProtectedContentPolicy.POLICY_VERSION` for
`privacyPolicyVersion` metadata. That couples index invalidation to a mixed
runtime facade.

Target direction:

- direct protected-path classification has its own lower-level policy version;
- document extraction has its existing extraction policy version;
- private document/RAG privacy config contributes through config hash or a
  lower-level RAG privacy policy version;
- tool-result redaction version changes must not invalidate a search index.

Do not change index metadata in the first implementation ticket unless the
policy-version split is explicit and tested.

## Remaining Baseline Classification

### Direct path/sanitizer migration candidates

These can be reduced after `ProtectedWorkspacePaths` exists:

- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RetrieveTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- part of `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- part of `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.ProtectedContentPolicy`

These are not all identical:

- `RetrieveTool` is the cleanest first adopter because it needs only direct
  path omission and text sanitization.
- `RagService` also has runtime context-ledger dependencies and
  `ProtectedReadScopePolicy`, so it should not be the first proof of the path
  split.
- `GrepTool` also has private-mode search-line withholding and protected
  content note wording.
- `Indexer` also has policy-version metadata and private-document RAG policy.

### Private document decision candidates

These require a separate decision/value-object design:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`

Do not attack these before the direct path classifier split is proven.

### Protected read scope candidates

These require splitting lower-level privacy config facts from runtime
approval-scope behavior:

- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`

Do not move approval notes or approved protected-read handoff into core/tools.

### Separate architecture tracks

These are not part of the T349 policy decision:

- runtime-to-CLI session/memory/result contracts;
- RAG/runtime context ledger contracts;
- command/workspace execution contracts;
- SPI purity.

They need their own decision tickets.

## Next Implementation Ticket

T350 should be:

```text
[T350] Extract direct protected workspace path classifier
```

Recommended scope:

1. Add `dev.talos.safety.ProtectedWorkspacePaths`.
2. Prove parity with the direct-path behavior currently reached through
   `ProtectedPathPolicy.classify(workspace, rawPath)`.
3. Make runtime `ProtectedPathPolicy` delegate direct path classification to
   the safety class while keeping tool-call extraction and `ResourceDecision`
   adaptation in runtime.
4. Migrate `RetrieveTool` from `ProtectedContentPolicy` to:
   - `ProtectedWorkspacePaths.isProtectedPath(...)`;
   - `ProtectedContentSanitizer.sanitizeText(...)`;
   - local or lower-level protected-content note wording if needed.
5. Remove only the stale `RetrieveTool -> ProtectedContentPolicy` baseline
   entry if the architecture scanner proves it stale.

Expected result if scoped correctly:

- one runtime policy edge removed from tools;
- no protected-read/private-document behavior moved;
- no RAG/index metadata changes;
- no approval-scope behavior changes.

The counter reduction is not the reason to do T350. The reason is that direct
workspace protected-path classification gets the correct owner.

## Acceptance Criteria

- T349 records a source-backed decision for the remaining protected-content,
  protected-path, private-document, and protected-read-scope edges.
- T349 explicitly rejects wholesale policy-class relocation.
- T349 names the lower-level owner for direct workspace protected-path
  classification.
- T349 separates tool-call resource classification from direct path
  classification.
- T349 separates pure privacy/config facts from runtime approval scope.
- T349 classifies the remaining policy baseline entries by future treatment.
- T349 names the next implementation ticket.
- T349 does not change production behavior.
- `validateArchitectureBoundaries` passes.
- `validateReleaseLedger` passes.
- `git diff --check` passes, allowing repository line-ending warnings only.
- No generated audit artifacts are committed.

## Verification

Planned before commit:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries validateReleaseLedger --no-daemon
```

Observed: passed.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- Putting workspace path classification into `dev.talos.safety` must not turn
  safety into a general policy bucket. Keep it JDK-only and forbid Talos layer
  imports through the existing `safety-no-talos-layers` rule.
- Moving private-document policy downward without splitting model-handoff and
  artifact-persistence decisions would weaken the trust boundary.
- Changing index privacy metadata without a named policy-version decision could
  cause unnecessary or insufficient reindexing.

## Known Follow-Ups

- T350: extract direct protected workspace path classifier and migrate the
  cleanest direct-path adopter.
- Follow-up: split protected-content note rendering from runtime facade where
  tools need non-runtime wording.
- Follow-up: design document content decision value object for extraction/tool
  metadata.
- Follow-up: split lower-level privacy config facts from runtime approval
  scope.
- Follow-up: handle RAG/runtime context and index metadata as separate tickets.
