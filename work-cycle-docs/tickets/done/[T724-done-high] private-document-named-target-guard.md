# [T724-done-high] Private Document Named-Target Guard

Status: done
Priority: high

## Evidence Summary

- Source: `current-0.10.0-full-two-model-20260608-082529-docs` capability audit
- Date: 2026-06-08
- Talos version / commit: `0.10.0` / `6c05f8f0b34110faa80a04630a98cd9a2544510e`
- Model/backend: Qwen installed-product audit
- Related finding: `F-0.10-AUDIT-003`

Expected behavior:

```text
In private mode, when the user asks about one named extractable private
document, the runtime should not let the model over-read sibling private
documents unless the user explicitly named them as current-turn targets.
```

Observed behavior:

```text
Qwen requested two private document reads for a prompt that named one target.
The runtime required approval and withheld content, so no leak occurred, but the
extra private document read was poor data minimization.
```

## Classification

Primary taxonomy bucket:

- `PERMISSION`

Secondary buckets:

- `TOOL_SURFACE`
- `TRACE_REDACTION`

Blocker level:

- release blocker

Why this level:

```text
This is not a confirmed leak, but it is a runtime-owned data-minimization guard
gap in the beta document/privacy lane.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Private document model handoff is centralized after extraction, but there is no
pre-execution private-document named-target guard before read_file extraction
and approval. The guard belongs in the tool-call execution path, near existing
path normalization and protected-path pre-execution checks.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/task/TaskContract.java`
- `src/main/java/dev/talos/core/privacy/PrivateDocumentContentPolicy.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`

## Goal

```text
Block same-turn private extractable document reads outside the current named
target set before extraction and before private-document handoff approval.
```

## Non-Goals

- No change to public document extraction.
- No broad fuzzy file-target correction.
- No change to protected read approval policy outside this private-document target guard.
- No autonomous expansion of private document targets.

## Implementation Notes

Guard `talos.read_file` when:

- private mode is active;
- requested target is an extractable document;
- current `TaskContract.expectedTargets()` is non-empty;
- requested path is not in expected targets or source evidence targets.

The blocked tool result should name only sanitized paths and explain that the
target is outside the current requested private document target set.

## Architecture Metadata

Capability:

- Private document extraction.

Operation(s):

- `read_file` on extractable PDF/DOCX/XLS/XLSX documents.

Owning package/class:

- `dev.talos.runtime.toolcall.ToolCallExecutionStage`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: high privacy/data-minimization risk.
- Approval behavior: block before extraction and before private-document handoff approval.
- Protected path behavior: existing protected policy remains unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: blocked tool result and trace should show sanitized target reason.
- Verification profile: none.
- Repair profile: none.

Outcome and trace:

- Outcome/truth warnings: final answer must not imply the blocked sibling document was inspected.
- Trace/debug fields: existing tool-result trace should record the block.

Refactor scope:

- Small policy helper or execution-stage helper is allowed.
- No rewrite of tool-call loop.

## Acceptance Criteria

- In private mode, a prompt naming `private-report.pdf` blocks a model call to `read_file private-report.docx` before extraction and approval.
- A prompt explicitly naming both `private-report.pdf` and `private-report.docx` allows both.
- Public document extraction remains unchanged.
- Private XLSX named-target behavior matches PDF/DOCX.
- Blocked result uses sanitized paths only.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Implemented:

- Added `PrivateDocumentNamedTargetGuard`.
- `ToolCallExecutionStage` now blocks private-mode `read_file` calls for
  extractable documents outside the current expected/source-evidence target
  set before extraction and before private document handoff approval.
- Blocked results use sanitized path diagnostics and trace
  `PRIVATE_DOCUMENT_NAMED_TARGET_SCOPE`.

Verification evidence:

- Focused RED tests failed before the guard, then passed after implementation.
- `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --tests "dev.talos.runtime.policy.*" --no-daemon` passed.
- `.\gradlew.bat check --no-daemon` passed.

Required deterministic regression:

- Tool-call execution-stage test for single-target private PDF over-read block.
- Tool-call execution-stage test for multi-target allowed behavior.
- Public document extraction unchanged test.
- Private XLSX named-target block test.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --tests "dev.talos.runtime.policy.*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use TDD.
- Do not bump version.
- Release-gate rerun remains separate.

## Known Risks

- The current task contract target extraction may need narrow normalization for simple document filenames.

## Known Follow-Ups

- Re-audit Qwen private PDF/DOCX/XLSX prompts after implementation.
