# T238 - Failed Workspace Switch Must Fence Next Relative Mutation

Status: done
Priority: medium

## Evidence Summary

Source audit:

- `local/manual-testing/user-perspective-broad-audit-20260511-080320/FINDINGS-USER-PERSPECTIVE-BROAD-AUDIT.md`
- Qwen transcript: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9849-9950`
- GPT-OSS final workspace state contains `should-not-be-on-desktop/`

Observed behavior:

```text
User asked Talos to change workspace to Desktop.
Talos correctly said workspace cannot be changed inside the current session.
User then asked to create a folder named should-not-be-on-desktop.
Talos created the folder in the original workspace.
```

Expected behavior:

```text
After a failed workspace-switch request, the next relative mutation is
ambiguous. Talos should require confirmation before mutating the old workspace.
```

## Classification

Primary taxonomy bucket:

- `WORKSPACE_BOUNDARY`

Secondary buckets:

- `INTENT_BOUNDARY`
- `PERMISSION`
- `OUTCOME_TRUTH`

## Goal

Avoid accidental mutations in the old workspace after the user tried to move to
another workspace.

## Acceptance Criteria

- When a turn is classified as unsupported workspace switch/change, record a
  short-lived session flag.
- If the next user turn is a relative workspace mutation, do not mutate
  immediately.
- The response must say the current workspace is still the old workspace and ask
  whether to apply the change there.
- If the user confirms, the mutation proceeds normally with approval.
- Absolute/sandboxed paths still obey existing workspace boundary policy.
- The flag clears after a clarifying answer, a `/workspace` command plus clear
  confirmation, or a non-mutating unrelated turn according to the chosen design.

## Non-Goals

- No in-session workspace switching.
- No support for writing to Desktop from an existing Talos session.
- No weakening of workspace sandbox boundaries.

## Suggested Tests

- Unit/integration: unsupported workspace switch followed by `Create folder X`
  produces confirmation, no `mkdir`.
- Follow-up confirmation then creates inside the current workspace.
- Normal folder creation without prior failed workspace switch still works.
- `/workspace` output remains truthful.

## Resolution

- Added short-lived session memory for failed workspace-switch requests.
- Added a one-turn pending confirmation state for the next relative mutation.
- The first relative mutation after a failed switch now produces a deterministic
  clarification instead of running tools.
- A clear confirmation replays the saved mutation request into the unchanged
  current workspace with the normal tool loop, approval, checkpointing, and
  verification path.
- Non-confirming or unrelated turns clear the short-lived fence.

## Verification

- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.failedWorkspaceSwitchFencesNextRelativeFolderMutation' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.confirmationAfterWorkspaceFenceAppliesSavedRelativeMutation' --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest`
- `.\gradlew test`
- `.\gradlew build`
