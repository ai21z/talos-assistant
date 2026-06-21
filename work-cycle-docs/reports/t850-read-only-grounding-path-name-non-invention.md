# T850 Read-Only Grounding Path-Name Non-Invention

Status: implemented; awaiting live scn-10 review before ticket closeout
Date: 2026-06-22
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Summary

T850 adds a narrow current-turn frame guard for read-only workspace answers. The
manual T842 evidence showed qwen could infer a project name from the workspace
directory path even when the user asked Talos to state only facts from files read
in the turn.

The implementation does not add a broad factuality verifier and does not change
retrieval, tools, mutation policy, or answer rendering. It tightens the
model-facing evidence rule for non-mutating read-only/workspace-inspection turns.

## Implementation

Changed production code:

- `CurrentTurnCapabilityFrame` now appends a `[FileGroundedAnswer]` block for
  non-mutating `READ_ONLY_QA`, `WORKSPACE_EXPLAIN`, and `DIAGNOSE_ONLY` turns.

The block states:

- workspace path/name metadata is not file evidence;
- workspace paths may be used as location labels only;
- a workspace directory name must not be presented as a project name or other
  file-grounded fact unless it appears in current-turn read/search/list results;
- when the user asks for facts from files, the model should state only facts
  observed in those results, otherwise say the inspected files do not state it.

Not changed:

- mutation classification;
- visible tool selection;
- RAG/retrieval ranking;
- workspace manifest construction;
- final-answer verifier behavior.

## Deterministic Coverage

Added:

- `CurrentTurnCapabilityFrameTest.readOnlyFileGroundedPromptSeparatesWorkspacePathMetadataFromFileEvidence`

Red-first result:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest.readOnlyFileGroundedPromptSeparatesWorkspacePathMetadataFromFileEvidence" --no-daemon
```

Result: FAIL before implementation because the frame did not contain the
workspace-path-not-file-evidence boundary.

Focused green result:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest.readOnlyFileGroundedPromptSeparatesWorkspacePathMetadataFromFileEvidence" --no-daemon
```

Result: PASS after implementation.

Related focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest" --tests "dev.talos.runtime.policy.CurrentTurnPromptInstructionsTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
```

Result: PASS.

## Remaining Review Gate

T850 is not closed by this report.

Before closeout, rerun the T842/scn-10 grounding/no-invention prompt on qwen and
confirm the answer does not infer `loqj-cli` or any project name from the
workspace path when no inspected file states that name. A two-model rerun is
useful for parity, but qwen is the model that produced the original finding.
