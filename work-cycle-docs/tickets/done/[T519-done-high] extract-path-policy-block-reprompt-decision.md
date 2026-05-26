# [T519] Extract path policy block reprompt decision

## Status

Done.

## Context

T518 selected the pre-approval path-policy block branch as the next coherent `ToolCallRepromptStage` ownership move. The branch is not generic orchestration; it is a specialized recovery path for wrong-target or path-policy-blocked mutation attempts.

## Changes

- Added `dev.talos.runtime.toolcall.ToolRepromptPathPolicyBlockedDecision`.
- Updated `ToolCallRepromptStage` to delegate path-policy block handling through `ToolRepromptPathPolicyBlockedDecision.tryHandle(...)`.
- Moved expected-target scope repair invocation, direct exact-replacement scheduling, repair trace recording, compact repair retry execution, and fallback stop-answer rendering out of `ToolCallRepromptStage`.
- Updated ownership tests so `ExpectedTargetScopeRepairPlanner` remains the repair planner, while the new path-policy decision owns when that planner is invoked from the reprompt stage.

## Preserved Behavior

- No path-policy block falls through to later reprompt decisions.
- Path-policy block without a repair plan still stops and clears native calls with the existing stop answer.
- Path-policy block with exact expected-target replacement still:
  - resets the failure decision to continue
  - raises the expected-target-scope pending obligation
  - records the prompted repair key
  - records the repair trace
  - schedules the runtime-owned `talos.edit_file` native call directly
- Path-policy block with compact repair still goes through the existing `ToolRepromptChatExecutor` path.

## Non-Changes

- No approval-denial behavior changes.
- No denied-mutation response behavior changes.
- No stale-edit reread behavior changes.
- No terminal read-only answer behavior changes.
- No partial-success fall-through behavior changes.
- No default failure-policy behavior changes.
- No source-evidence repair behavior changes.
- No target-readback compact repair behavior changes.
- No remaining-target obligation or overlay continuation behavior changes.

## Verification

- RED: focused tests failed before implementation because `ToolRepromptPathPolicyBlockedDecision` did not exist.
- GREEN: focused owner and behavior tests passed after extraction.
- Focused wider tests passed:
  - `ToolRepromptPathPolicyBlockedDecisionTest`
  - `ExpectedTargetScopeRepairPlannerTest`
  - `ToolCallRepromptStageTest`
  - `ToolCallRepromptStageToolSurfaceTest`
  - `ToolCallLoopTest.expectedTargetScopeRepairIncludesAlreadyWrittenStaticWebReadbacks`

## Next Step

Inspect the post-T519 `ToolCallRepromptStage` shape before choosing T520. Do not assume the next ticket is another extraction.
