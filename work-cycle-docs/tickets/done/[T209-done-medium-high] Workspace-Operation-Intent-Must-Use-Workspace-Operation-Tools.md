# T209 - Workspace Operation Intent Must Use Workspace Operation Tools

Severity: medium-high
Status: done

## Problem

The T61-Q managed llama.cpp full audit shows GPT-OSS can respond to a workspace move request with generic write/edit behavior instead of using `talos.move_path`.

Talos catches the result as partial, but the model should be given a tighter workspace-operation action contract and tool surface so move/copy/rename/mkdir tasks naturally use the matching native workspace operation tools.

## Evidence

Audit:
`local/manual-testing/llama-cpp-t61q-full-e2e-audit-20260507-215146/`

Prompt:
`Move workspace-notes/readme-renamed.md to archive/readme-renamed.md.`

GPT-OSS outcome:
- created or updated `archive/readme-renamed.md`,
- left `workspace-notes/readme-renamed.md`,
- attempted invalid `talos.edit_file` calls,
- runtime marked the turn partial with:
  - `workspace-notes/readme-renamed.md: expected target was not successfully mutated.`

Final GPT-OSS workspace contains both:
- `archive/readme-renamed.md`
- `workspace-notes/readme-renamed.md`

Qwen comparison:
- Qwen used `talos.move_path`.
- Final Qwen workspace contains only `archive/readme-renamed.md`.

## Scope

- Classify explicit move/copy/rename/mkdir requests as workspace-operation turns, not generic file edit/create turns.
- Narrow the visible tool surface for workspace operation turns to the appropriate operation tools.
- Preserve expected-target verification and changed-files summaries for source and destination effects.
- Keep failure-dominant/partial output when the model still chooses wrong tools or provides invalid arguments.

## Acceptance

- Tests cover an explicit move request and assert the first backend prompt exposes `talos.move_path` rather than generic write/edit tools.
- Tests cover explicit copy and rename requests with their matching tools.
- Tests cover a model trying generic write/edit under a move obligation and assert deterministic partial/failure classification remains correct.
- Existing batch workspace operation behavior still passes.

## Non-Goals

- Do not change static web verification.
- Do not implement filesystem deletes beyond the existing workspace operation tools.
- Do not rely on prompt wording alone without narrowing the tool surface.

## Implementation Notes

Implemented a `WorkspaceOperationIntent` detector and wired it through:
- action obligation selection,
- current-turn capability framing,
- tool surface planning,
- compact mutation retry tool selection,
- deterministic no-tool retry failure wording.

Explicit move/copy/rename/mkdir requests now select `WORKSPACE_OPERATION_REQUIRED` and expose only the matching operation tool.

## Verification

Automated checks:
- `.\gradlew.bat test --tests dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.policy.ActionObligationPolicyTest --tests dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest --tests dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest --tests dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest --tests dev.talos.runtime.task.TaskContractResolverTest --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`

Focused two-model audit:
`local/manual-testing/llama-cpp-t209-focused-re-audit-20260507-231118/FINDINGS-LLAMA-CPP-T209-FOCUSED-RE-AUDIT.md`

Result:
- Qwen and GPT-OSS both received only the matching operation tool for move/copy/rename/mkdir.
- Qwen and GPT-OSS both used the matching tool successfully.
- The previous no-tool mkdir completion did not reproduce.
