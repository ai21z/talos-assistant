# [T357-done-high] Private Document Policy Decision Contract

Status: done
Priority: high
Date: 2026-05-22
Branch: `T357`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T356-done-medium] move-indexer-protected-content-version-to-safety`

## Evidence Summary

- Source: post-T356 architecture continuation after PR #21 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `2d817cb7823eecb6f426c4fca95eaba25ed37d95`.
- Beta push CI: run `#59`, `Beta Dev CI`, push event for `2d817cb7`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T357`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: documentation-only architecture decision ticket.
- Verification status: passed.

## Verification

- `git diff --check`: passed.
- `.\gradlew.bat validateReleaseLedger validateArchitectureBoundaries --no-daemon`:
  passed.
- `.\gradlew.bat check --no-daemon`: passed.

## Problem

T346 through T356 removed real lower-layer ownership lies:

- sink-safe logging moved to `dev.talos.safety`;
- pure text redaction moved to `ProtectedContentSanitizer`;
- direct protected workspace path classification moved to
  `ProtectedWorkspacePaths`;
- read-only privacy mode facts moved to `PrivacyConfigFacts`;
- RAG/indexing direct protected-path and sanitizer dependencies moved away
  from the mixed runtime `ProtectedContentPolicy` facade.

The remaining private-document baseline rows are not the same kind of work.
They are not isolated sanitizer/path facts. They are a mixed privacy decision
cluster spanning:

- document extraction provenance;
- model-context handoff;
- raw artifact persistence;
- RAG indexing permission;
- private-mode defaults;
- protected-path handling;
- user-facing decision reasons;
- runtime approval prompts and trace metadata.

Mechanically moving `PrivateDocumentPolicy` into `core` would be wrong. It
would reduce the ratchet number while smuggling runtime approval and handoff
semantics into lower layers. Mechanically deleting one caller at a time would
also be wrong unless the replacement contract is already clear.

## Current Baseline

After T356, `config/architecture-boundary-baseline.txt` has `39` entries.
The remaining direct `PrivateDocumentPolicy` baseline rows are exactly:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`

These three callers consume different parts of the same mixed policy:

- `DocumentExtractionService` calls only `modelHandoffAllowed(...)`.
- `Indexer` calls `ragIndexAllowed(...)` and `decisionReason(...)`.
- `ReadFileTool` calls `privateDocumentContent(...)`,
  `rawArtifactPersistenceAllowed(...)`, `ragIndexAllowed(...)`, and
  `decisionReason(...)`, while also consuming
  `DocumentExtractionResult.modelHandoffAllowed()`.

Additional upper-layer runtime/CLI consumers are not baseline violations but
must remain part of the design:

- `ToolCallExecutionStage` uses `PrivateDocumentPolicy.modelHandoffNote(...)`
  for private-document model-handoff approval/withholding messages.
- `/privacy` uses the private-document opt-in accessors for status output.
- `/show` uses `decisionReason(...)` for local-display extracted document
  output.

## Source Findings

### `PrivateDocumentPolicy` is mixed by construction

`PrivateDocumentPolicy` currently combines lower facts and runtime decisions:

- document-format facts from `FileCapabilityPolicy`;
- extraction intent from `DocumentExtractionRequest`;
- direct protected-path classification through `ProtectedContentPolicy`;
- private-mode and RAG config through `ProtectedReadScopePolicy`;
- document-extraction opt-ins from `privacy.document_extraction`;
- model-handoff, raw artifact persistence, and RAG indexing decisions;
- user-facing decision strings and scope notes.

This makes it a facade, not a package owner.

### `DocumentExtractionService` should not own model-context policy

`DocumentExtractionService` extracts local text, sanitizes it, returns status,
warnings, provenance, and safe text. It currently also stores
`modelHandoffAllowed` in `DocumentExtractionResult`.

That boolean is a runtime/tool-context decision. It depends on extraction
intent, private mode, protected-path status, approved protected-read model
handoff, and private-document opt-ins. Core extraction should not decide what
enters model context. Core extraction should report extraction facts.

### `Indexer` needs a RAG indexing decision, not a model-handoff decision

`Indexer` should continue to block unsafe private-document chunks from RAG.
That is not the same decision as model context handoff for a direct read.

Indexing needs a narrow decision:

```text
Given cfg, workspace root, document path, extraction intent INDEX, and format
info, may this extracted document text be indexed?
```

It does not need approval prompt text, model-handoff notes, raw artifact
persistence policy, or tool-output metadata.

### `ReadFileTool` needs tool-output metadata

`ReadFileTool` produces a `ToolResult` with `ToolContentMetadata`. That
metadata drives runtime model-handoff approval, trace capture, raw persistence,
and context withholding.

This is runtime/tool handoff territory. The tool should not assemble the
metadata by calling five static methods on a mixed runtime policy. It should
consume a single decision value produced by an explicit policy owner.

### Existing metadata shape is close but not enough

`ToolContentMetadata` already has the fields needed by the runtime:

- `privacyClass`;
- `source`;
- `sourcePath`;
- `modelHandoffAllowed`;
- `rawArtifactPersistenceAllowed`;
- `ragIndexAllowed`;
- `decisionReason`.

But `ToolContentMetadata` is a tool-result metadata type. It should not become
the core extraction or indexing decision contract. Core extraction/indexing
would then depend on a tools package, which is the same architecture problem in
a different direction.

## Decision

### 1. Do not move `PrivateDocumentPolicy` wholesale

`PrivateDocumentPolicy` remains runtime-owned until its responsibilities are
split. Moving it into `core`, `tools`, or `safety` as a whole is rejected.

### 2. Split private-document policy by consumer decision

The correct target is not one universal mega-policy. It is a small set of
explicit decision contracts:

```text
core extraction:
  owns document extraction facts only

core privacy/indexing:
  owns narrow RAG indexing decisions for extracted document text

runtime/tool handoff:
  owns model-context handoff, private-document approval notes,
  raw artifact persistence, and ToolContentMetadata adaptation
```

This keeps each decision near the boundary that can enforce it.

### 3. Add a neutral private-document decision value before migrating callers

The first implementation ticket should introduce a neutral value object that
can be returned by runtime/tool policy without forcing tools to call several
static methods.

Recommended package:

```text
dev.talos.core.privacy
```

Recommended type:

```text
DocumentContentDecision(
    boolean privateDocumentContent,
    boolean modelHandoffAllowed,
    boolean rawArtifactPersistenceAllowed,
    boolean ragIndexAllowed,
    String reason
)
```

Why `core.privacy`:

- it already owns read-only privacy facts through `PrivacyConfigFacts`;
- it can be imported by runtime and tools without reversing dependencies;
- it must not import runtime, tools, CLI, engine, SPI, or app packages;
- it is not a sink-safety primitive, so it does not belong in
  `dev.talos.safety`.

This value object is data only. It must not parse `Config`, classify paths,
prompt for approval, read files, mutate privacy mode, or format approval text.

### 4. Keep computation out of core extraction for now

The computation can remain in runtime policy initially:

```text
PrivateDocumentPolicy.decide(cfg, request, info) -> DocumentContentDecision
```

This is a transitional contract. It improves `ReadFileTool` immediately by
replacing repeated static calls with one explicit decision, but it does not by
itself remove the remaining baseline edges.

That is acceptable. A correct preparatory contract is better than pretending a
code move solved ownership.

### 5. Extract a separate indexing decision after the value object exists

The next baseline-reducing implementation should not use the broad
tool-handoff decision from core indexing. `Indexer` needs a narrower index
decision.

Recommended target:

```text
dev.talos.core.privacy.PrivateDocumentIndexingPolicy
```

Initial responsibility:

```text
mayIndexExtractedDocument(Config cfg, DocumentExtractionRequest request,
                          FileCapabilityPolicy.FormatInfo info)
```

Allowed dependencies:

- `Config`;
- `CfgUtil`;
- `PrivacyConfigFacts`;
- `ProtectedWorkspacePaths`;
- `DocumentExtractionRequest`;
- `DocumentExtractionIntent`;
- `FileCapabilityPolicy.FormatInfo`.

Forbidden dependencies:

- runtime policy;
- tools metadata;
- CLI status text;
- approval gates;
- trace capture;
- command execution;
- RAG context ledger records.

This is the likely first baseline-reducing private-document implementation
after the preparatory value-object ticket.

### 6. Remove `DocumentExtractionService` model-handoff ownership last

`DocumentExtractionService` is the most delicate caller because
`DocumentExtractionResult.modelHandoffAllowed()` is already consumed by:

- `ReadFileTool`;
- `GrepTool`;
- `/grep`;
- tests covering private-mode document extraction;
- runtime approval/withholding flows indirectly through tool metadata.

The correct end state is for extraction to return extracted facts, while
runtime/tool adapters attach handoff decisions. That requires a compatibility
transition and broader tests. It should not be the first private-document
implementation ticket.

## Rejected Options

### Rejected: move `PrivateDocumentPolicy` to `core.privacy`

The class still owns runtime approval and handoff semantics. Moving it would
make lower layers responsible for model-context approval wording, raw artifact
persistence, and protected-read handoff.

### Rejected: make `ToolContentMetadata` the core decision contract

`ToolContentMetadata` is correct for tool results, but core extraction and core
indexing must not depend on `dev.talos.tools`.

### Rejected: delete only `DocumentExtractionService -> PrivateDocumentPolicy`

That would attack the hardest edge first and likely spread model-handoff logic
into extraction, grep, slash commands, or tests without a stable contract.

### Rejected: collapse RAG indexing and model-handoff decisions

RAG indexing and direct read model-handoff are different privacy events.
Sharing a value object is acceptable; sharing one enforcement policy is not.

## Implementation Sequence

### T358: preparatory contract, no baseline decrement required

Recommended title:

```text
[T358] Add private document content decision value
```

Scope:

- add `dev.talos.core.privacy.DocumentContentDecision`;
- add unit tests for null/default normalization if needed;
- add `PrivateDocumentPolicy.decide(...)`;
- update `ReadFileTool` to call `decide(...)` once and adapt the returned
  value into `ToolContentMetadata`;
- keep existing behavior byte-for-byte equivalent where practical;
- do not remove the `ReadFileTool -> PrivateDocumentPolicy` baseline row yet
  unless validation proves the edge is actually gone, which is unlikely.

Verification:

- `DocumentExtractionServiceTest`;
- `ReadFileToolTest`;
- `ProtectedReadScopeIntegrationTest` private-document model-handoff cases;
- `validateArchitectureBoundaries`;
- full `check`.

### T359 or later: narrow RAG indexing policy

Scope:

- add a core-owned private-document indexing policy;
- make runtime `PrivateDocumentPolicy.ragIndexAllowed(...)` delegate to it;
- migrate `Indexer` only;
- remove only the stale `Indexer -> PrivateDocumentPolicy` baseline entry if
  validation proves it stale.

Expected baseline impact:

- `39 -> 38` if scoped correctly.

### Later: extraction model-handoff ownership transition

Scope:

- remove model-context decision from `DocumentExtractionService`;
- preserve compatibility for existing `DocumentExtractionResult` consumers or
  migrate them in a coordinated ticket;
- move runtime/tool handoff decisions to a runtime adapter;
- broaden private-document approval and trace tests.

This is a higher-risk change and should not be mixed with indexing or metadata
cleanup.

## Expected T357 Result

T357 intentionally does not change production code.

Expected state:

- architecture baseline remains `39`;
- new violations remain `0`;
- stale baseline entries remain `0`;
- no runtime behavior changes;
- next implementation work has an explicit contract boundary.

Confidence: high.
