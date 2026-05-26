# [T521] Extract source evidence repair decision

## Status

Done.

## Context

Post-T520 inspection showed that `ToolCallRepromptStage` still owned the source-evidence exact repair execution branch. The planner was already separate, but the stage still decided when to invoke it, raised pending obligations, recorded prompted repair keys, and executed the compact retry.

## Changes

- Added `dev.talos.runtime.toolcall.ToolRepromptSourceEvidenceRepairDecision`.
- Updated `ToolCallRepromptStage` to delegate source-evidence exact repair handling through `ToolRepromptSourceEvidenceRepairDecision.tryHandle(...)`.
- Kept `SourceEvidenceExactRepairPlanner` as the planner and moved only the reprompt decision/execution glue out of the stage.
- Added focused ownership and behavior coverage.

## Preserved Behavior

- No source-evidence repair plan returns `Optional.empty()` and falls through.
- A source-evidence repair plan still raises an expected-target pending obligation for the repaired path.
- The prompted repair key is still recorded exactly once.
- Compact repair retry still goes through `ToolRepromptChatExecutor`.
- Retry name remains `source-evidence exact compact repair`.
- Prompt content and required exact evidence frame remain planner-owned and unchanged.

## Non-Changes

- No approval-denial behavior changes.
- No denied-mutation response behavior changes.
- No path-policy block behavior changes.
- No stale-reread behavior changes.
- No terminal read-only behavior changes.
- No successful-mutation behavior changes.
- No repair-budget behavior changes.
- No target-readback or overlay-continuation behavior changes.

## Verification

- RED: focused tests failed before implementation because `ToolRepromptSourceEvidenceRepairDecision` did not exist.
- GREEN: focused ownership and behavior tests passed after extraction.
- Focused wider tests passed:
  - `ToolRepromptSourceEvidenceRepairDecisionTest`
  - `SourceEvidenceExactRepairPlannerTest`
  - `SourceDerivedEvidenceGuardTest`
  - `ToolCallRepromptStageTest`
  - `ToolCallLoopTest.mutationContinuationIncludesSourceEvidenceReadbacksForSourceDerivedWrite`

## Next Step

Inspect the post-T521 `ToolCallRepromptStage` shape before choosing T522. Do not assume another implementation ticket until the remaining branches are rechecked.
