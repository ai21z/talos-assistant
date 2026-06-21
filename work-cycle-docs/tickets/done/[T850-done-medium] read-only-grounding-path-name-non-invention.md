# [T850-done-medium] Read-Only Grounding Path-Name Non-Invention

Status: done
Priority: medium
Type: implementation
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

Implementation state: deterministic current-turn frame guard implemented;
qwen scn-10 live review passed.

## Evidence Summary

- Source: T842 manual beta scenario evidence.
- Date: 2026-06-21.
- Talos version / commit: `0.10.5`; local summaries do not capture a commit.
- Model/backend: qwen failure under managed `llama.cpp`; gpt-oss passed the
  cross-model check.
- Workspace fixture: `local/beta-pre-release-test-scenarios/scn-10-grounding-no-invention`.
- Raw transcript path: local T842 artifacts.
- Trace path or `/last trace` summary: local T842 artifacts.
- File diff summary: none; read-only turn.
- Approval choices: none.
- Verification status: read-only answer.

Redacted prompt sequence:

```text
Only state facts from the files you read.
```

Expected behavior:

```text
The answer only states workspace facts grounded in files read during the turn.
It does not infer a project name from the workspace path.
```

Observed behavior:

```text
qwen invented or inferred the project name "loqj-cli" even though no file read
in that turn contained it. gpt-oss correctly said no file contained a project
name.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `CURRENT_TURN_FRAME`
- `MODEL_COMPETENCE`

Blocker level:

- candidate follow-up

Why this level:

```text
This is model-sensitive and did not mutate files, but beta read-only answers
should avoid path-derived facts when the user asks for file-grounded facts only.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell qwen to be more careful.
```

Architectural hypothesis:

```text
Read-only QA prompts and/or answer shaping need a stronger boundary between
workspace path metadata and file-grounded facts when the user explicitly asks
for facts from files.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/llm/SystemPromptBuilder.java`
- read-only answer grounding/retry components in `src/main/java/dev/talos/cli/modes`
- current-turn task contract wording.

Why a one-off patch is insufficient:

```text
The failure is not the word "loqj-cli"; it is treating workspace path metadata
as file evidence.
```

## Goal

```text
Prevent read-only answers from presenting workspace path-derived names as
file-grounded facts when the requested evidence scope is "files read this turn."
```

## Non-Goals

- No broad factuality verifier.
- No production retrieval/ranking change.
- No hiding workspace path metadata from all prompts unless proven necessary.
- No change to mutation behavior.

## Implementation Notes

Prefer a narrow prompt/frame or answer-grounding adjustment that distinguishes
workspace location from inspected file content. If deterministic post-answer
grounding exists for named facts, extend it conservatively.

## Architecture Metadata

Capability:

- Read-only workspace QA grounding.

Operation(s):

- read/answer only.

Owning package/class:

- Current-turn prompt/task framing or read-only grounding owner.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium; answer truthfulness.
- Approval behavior: none.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none.
- Evidence obligation: answer facts should cite/read file evidence.
- Verification profile: readback-only, not semantic proof.
- Repair profile: grounding retry if applicable.

Outcome and trace:

- Outcome/truth warnings: grounded/unsupported distinction should improve.
- Trace/debug fields: prompt frame should show the bounded evidence rule.

Refactor scope:

- Allowed: narrow prompt/grounding tests and wording changes.
- Forbidden: broad answer verifier or model-specific branching without evidence.

## Acceptance Criteria

- The scn-10 prompt shape does not infer a project name from the workspace path.
- Answers may still cite file paths as paths when relevant, but not as proof of
  project identity.
- Existing read-only QA and workspace explain tests remain green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit or characterization test for read-only grounded answer framing.
- If practical, scripted model/provider fixture proving path-derived name is
  corrected or disallowed.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.*" --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Implementation Evidence

Implementation batch:

- Added a `[FileGroundedAnswer]` section to `CurrentTurnCapabilityFrame` for
  non-mutating `READ_ONLY_QA`, `WORKSPACE_EXPLAIN`, and `DIAGNOSE_ONLY` turns.
- The section states that workspace path/name metadata is not file evidence and
  that workspace directory names must not be presented as project names or other
  file-grounded facts unless observed in current-turn read/search/list results.
- The implementation does not change mutation classification, tool selection,
  retrieval behavior, or final-answer verification.

Deterministic test added:

- `CurrentTurnCapabilityFrameTest.readOnlyFileGroundedPromptSeparatesWorkspacePathMetadataFromFileEvidence`

Red-first focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest.readOnlyFileGroundedPromptSeparatesWorkspacePathMetadataFromFileEvidence" --no-daemon
```

Result: FAIL before implementation because the frame did not include the
workspace-path-not-file-evidence boundary.

Focused green gates:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest.readOnlyFileGroundedPromptSeparatesWorkspacePathMetadataFromFileEvidence" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest" --tests "dev.talos.runtime.policy.CurrentTurnPromptInstructionsTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
```

Result: PASS.

Review status:

- T850 is closed.
- The T842/scn-10 grounding/no-invention prompt was rerun on qwen against the
  current installed build at implementation commit
  `679dd17fe137e119567971afe445a7f5fca99147`.
- Artifact path:
  `local/beta-pre-release-test-scenarios/runs/t850-679dd17f/qwen2.5-coder-14b/scn-10-grounding-no-invention/transcript.txt`.
- Talos used one read-only tool call, `talos.read_file -> calc.py [ok]`.
- The answer said it could not determine the project name from files in the
  workspace and did not infer `loqj-cli` from the workspace path.
- Workspace `git status` and `git diff` artifacts were empty; the read-only
  turn made no mutation.

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version.
- Add a `CHANGELOG.md` `Unreleased` note if production behavior changes.

## Known Risks

- Over-tightening could make useful workspace-path context unavailable.

## Known Follow-Ups

- Re-run T842 scn-10 on qwen after implementation.
