# T243 - Workspace Confirmation Replay Must Recompute Native Tool Surface

Status: done

Closed: 2026-05-11

Severity: high

## Problem

The T240 fix replaced stale current-turn frames, but the live focused re-audit still shows a broken confirmation turn after a failed workspace switch.

The saved request is replayed far enough for the task contract to become `FILE_CREATE`, but the native tool surface can remain read-only from the previous turn. The prompt frame then says:

`type: FILE_CREATE mutationAllowed: true`

while also saying:

`visibleTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve`

Result: the model cannot create the requested folder, and Talos blocks the attempted write/read fallback.

## Evidence

Audit:
`local/manual-testing/t239-t242-focused-reaudit-20260511-153616`

Transcript:
- Qwen confirmation frame: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` around the confirmation turn.
- GPT-OSS confirmation frame: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around the confirmation turn.
- Both provider bodies show the replayed user request `Create a folder named should-not-be-on-desktop.` with read-only visible tools.

## Scope

- When `WorkspaceBoundaryPreflight` replaces the latest user request with the saved mutation request, force native tool-surface recomputation from that effective request.
- The recomputed surface must expose the appropriate workspace operation tool, for example `talos.mkdir`.
- Preserve stale-frame replacement from T240.
- Keep rejection/no-confirmation paths non-mutating.

## Acceptance

- A test simulates a stale read-only native tool surface before confirmation.
- The confirmation turn recomputes to a mutating workspace-operation surface and exposes `talos.mkdir`.
- The folder is created after approval.
- Prompt audit summary and `CurrentTurnCapability` frame agree.
- The live focused audit confirmation probe passes for Qwen and GPT-OSS.

## Resolution

- `AssistantTurnExecutor.execute` now treats a workspace-boundary replayed request as a reason to force native tool-surface recomputation.
- The confirmation path still replaces the latest user message with the saved original mutation request, but now the native tool list is rebuilt from that effective request instead of carrying a stale read-only override.
- The regression test now simulates the live failure mode by entering confirmation with stale read-only native specs and asserts that the outgoing frame exposes `talos.mkdir`.

## Verification

- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.confirmationAfterWorkspaceFenceAppliesSavedRelativeMutation' --no-daemon`
- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.failedWorkspaceSwitchFencesNextRelativeFolderMutation' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.confirmationAfterWorkspaceFenceAppliesSavedRelativeMutation' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionReplacesApologyNonAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionKeepsConcreteModelAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionReplacesContradictoryYesAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionKeepsAgreeingYesAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.compoundWorkspaceOperationCanApplyBatchThroughVisibleSurface' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.hiddenWorkspaceOperationToolIsRejectedBeforeExecution' --no-daemon`
- `.\gradlew test --no-daemon`
- `.\gradlew build --no-daemon`
