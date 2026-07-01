# T850 Read-Only Grounding Path-Name Non-Invention

Status: done
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

## Live Closeout Evidence

The T842/scn-10 grounding/no-invention prompt was rerun on qwen against the
current installed build at commit
`679dd17fe137e119567971afe445a7f5fca99147`.

Artifact path:

```text
local/beta-pre-release-test-scenarios/runs/t850-679dd17f/qwen2.5-coder-14b/scn-10-grounding-no-invention/transcript.txt
```

Result:

- Talos classified the turn as `WORKSPACE_EXPLAIN` with mutation disabled.
- The only tool call was `talos.read_file -> calc.py [ok]`.
- `calc.py` does not contain `loqj-cli` or any project-name declaration.
- The answer said it could not determine the project name from files in the
  workspace.
- The answer did not infer `loqj-cli` from the workspace path.
- Workspace `git status` and `git diff` artifacts were empty.

T850 is closed by this live review.
