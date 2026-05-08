# T230 - Workspace Boundary And Natural Mkdir Intent

Status: done
Severity: medium

## Problem

Talos can create directories inside the current workspace with `talos.mkdir`, but
the natural-language workspace-operation detector is too narrow. Phrases such as
`create a new dir called notes` can fall back to the broad mutation surface
instead of the deterministic mkdir-only surface.

Talos also does not clearly document or enforce that the current workspace is
session-bound. A user can ask to change workspace mid-session, and the model may
try to comply even though the runtime has no supported workspace-switch action.

## Scope

- Document that Talos operates inside the workspace selected at launch/session
  start.
- Document that `/workspace` is informational and does not switch workspace.
- Update the README tool list to include current workspace-operation tools.
- Recognize common natural mkdir phrases such as `new dir/folder
  named/called X`.
- Route standalone directory-creation requests to `talos.mkdir`.
- Return a deterministic unsupported-capability answer for natural workspace
  switching requests.

## Non-Goals

- Do not implement hot workspace switching.
- Do not weaken workspace sandbox/path containment.
- Do not change mixed directory-plus-file creation behavior; those turns still
  need the broader mutation surface.

## Acceptance

- Tests prove natural mkdir phrases narrow the tool surface to `talos.mkdir`.
- Tests prove mixed directory-plus-file creation keeps file write tools.
- Tests prove workspace-switch requests are direct answers, with no tool calls.
- README and `/workspace` wording describe the workspace boundary plainly.

## Verification

- `.\gradlew.bat test --tests dev.talos.runtime.workspace.WorkspaceOperationIntentTest --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest.naturalDirectoryCreationRequestsExposeOnlyMkdirTool --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest.mixedDirectoryAndExactFileCreateKeepsFileWriteSurface --tests dev.talos.runtime.task.TaskContractResolverTest.workspaceSwitchRequestsAreUnsupportedDirectAnswerContracts --tests dev.talos.cli.modes.AssistantTurnExecutorTest*workspaceSwitchRequestGetsDeterministicUnsupportedAnswer --tests dev.talos.cli.repl.slash.WorkspaceCommandsTest*spec_description_says_show_only --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.workspace.WorkspaceOperationIntentTest --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest --tests dev.talos.runtime.task.TaskContractResolverTest --tests dev.talos.runtime.policy.ActionObligationPolicyTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.cli.repl.slash.WorkspaceCommandsTest --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat build --no-daemon`
