# [T520] Extract stale edit reread stop

## Status

Done.

## Context

Post-T519 inspection showed one small, coherent terminal branch still owned directly by `ToolCallRepromptStage`: the stale-edit reread hard stop. That branch was not generic orchestration. It owned failure wording, failure action selection, native-call clearing, and log-safe path formatting for `staleEditRereadIgnoredPath`.

## Changes

- Added `dev.talos.runtime.toolcall.ToolRepromptStaleEditRereadStop`.
- Updated `ToolCallRepromptStage` to delegate stale-reread stop handling through `ToolRepromptStaleEditRereadStop.tryHandle(...)`.
- Moved stale-reread failure wording, `FailureAction.ASK_USER`, `ToolFailurePolicyStopAnswer.render(...)`, native-call clearing, and `SafeLogFormatter.value(...)` logging out of the stage.
- Added focused ownership and behavior coverage.

## Preserved Behavior

- No stale-reread path returns `Optional.empty()` and falls through to later reprompt decisions.
- A stale-reread path still stops the loop.
- The failure decision remains `ASK_USER`.
- The final stop answer wording is preserved.
- Native calls are cleared.
- Log output still uses `SafeLogFormatter.value(...)`.

## Non-Changes

- No approval-denial behavior changes.
- No denied-mutation response behavior changes.
- No path-policy block behavior changes.
- No terminal read-only answer behavior changes.
- No successful-mutation behavior changes.
- No partial-success fall-through behavior changes.
- No repair-budget behavior changes.
- No source-evidence, target-readback, or overlay-continuation behavior changes.

## Verification

- RED: focused tests failed before implementation because `ToolRepromptStaleEditRereadStop` did not exist.
- GREEN: focused ownership and behavior tests passed after extraction.
- Focused wider tests passed:
  - `ToolRepromptStaleEditRereadStopTest`
  - `ToolCallRepromptStageTest`
  - `EditFailureRepairStateAccountingTest`
  - `ReadEvidenceStateAccountingTest`
  - `EditFilePreApprovalGuardTest`

## Next Step

Inspect the post-T520 `ToolCallRepromptStage` shape before choosing T521. Do not assume another implementation ticket until the remaining branches are rechecked.
