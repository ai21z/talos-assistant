# [T522] Extract target readback repair decision

## Status

Done.

## Context

Post-T521 inspection showed that `ToolCallRepromptStage` still owned the target-readback compact repair execution glue for both append-line preservation failures and old-string-miss failures. The planner already owned repair frame construction, but the stage still invoked the planner, raised pending obligations, recorded prompted keys, and executed compact retries.

## Changes

- Added `dev.talos.runtime.toolcall.ToolRepromptTargetReadbackRepairDecision`.
- Updated `ToolCallRepromptStage` to delegate target-readback repair handling through `ToolRepromptTargetReadbackRepairDecision.tryHandle(...)`.
- Moved append-line and old-string-miss pending-obligation setup, prompted-key recording, and compact retry execution out of the stage.
- Kept `TargetReadbackCompactRepairPlanner` as the planner for both repair kinds.
- Updated stale source-ownership tests to reflect that normal chat execution is now fully outside the stage.

## Preserved Behavior

- No target-readback repair plan returns `Optional.empty()` and falls through.
- Append-line repair still raises an append-line pending obligation.
- Old-string-miss repair still raises an old-string-miss pending obligation.
- Prompted path keys are still recorded before retry execution.
- Compact repair retry still goes through `ToolRepromptChatExecutor`.
- Retry names and repair prompts remain planner-owned and unchanged.

## Non-Changes

- No approval-denial behavior changes.
- No denied-mutation response behavior changes.
- No path-policy block behavior changes.
- No stale-reread behavior changes.
- No terminal read-only behavior changes.
- No successful-mutation behavior changes.
- No source-evidence repair behavior changes.
- No remaining-target obligation or overlay-continuation behavior changes.

## Verification

- RED: focused tests failed before implementation because `ToolRepromptTargetReadbackRepairDecision` did not exist.
- GREEN: focused ownership and behavior tests passed after extraction.
- Focused wider tests passed:
  - `ToolRepromptTargetReadbackRepairDecisionTest`
  - `TargetReadbackCompactRepairPlannerTest`
  - `ExpectedTargetProgressAccountingTest`
  - `ToolCallRepromptStageTest`
  - `ToolCallRepromptStageToolSurfaceTest`

## Next Step

Inspect the post-T522 `ToolCallRepromptStage` shape before choosing T523. Do not assume another implementation ticket until the remaining branches are rechecked.
