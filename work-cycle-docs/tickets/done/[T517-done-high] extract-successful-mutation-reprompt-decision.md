# [T517] Extract successful mutation reprompt decision

## Status

Done.

## Context

T516 selected the next implementation slice in the tool-reprompt stage: extract the all-success mutation continuation decision from `ToolCallRepromptStage` without changing runtime behavior, final-answer wording, static-web continuation behavior, expected-target fall-through, or P0 skip behavior.

## Decision

`ToolCallRepromptStage` should remain the ordered reprompt orchestrator. The all-success mutation branch is now owned by `ToolRepromptSuccessfulMutationDecision`.

The extracted owner handles only this branch:

- all calls in the iteration succeeded
- at least one mutation occurred
- no call failed

It preserves the existing outcomes:

- stop when static-web verification already passes
- request static-web continuation when the static-web planner returns a plan
- stop with mutation summaries when no repair or expected targets remain
- fall through for remaining static repair targets or expected mutation targets

## Changes

- Added `dev.talos.runtime.toolcall.ToolRepromptSuccessfulMutationDecision`.
- Updated `ToolCallRepromptStage` to delegate successful-mutation continuation decisions.
- Added focused ownership and behavior coverage for the extracted decision.
- Added an orchestration ownership assertion that `ToolCallRepromptStage` no longer owns static-web pass checking, static-web continuation planning, or P0 successful-mutation skip wording directly.

## Non-Changes

- No approval policy changes.
- No path policy changes.
- No stale-reread behavior changes.
- No terminal-read-only behavior changes.
- No failed-call or partial-success behavior changes.
- No prompt wording, final-answer wording, or trace wording changes.
- No static-web planner behavior changes.
- No generic overlay continuation behavior changes.
- No tool-surface narrowing changes.

## Verification

- RED: focused ownership/behavior tests failed before implementation because `ToolRepromptSuccessfulMutationDecision` did not exist.
- GREEN: focused ownership/behavior tests passed after extraction.
- Focused wider tests passed:
  - `ToolRepromptSuccessfulMutationDecisionTest`
  - `ToolCallRepromptStageTest`
  - `ToolCallRepromptStageToolSurfaceTest`
  - `StaticWebContinuationPlannerTest`

## Next Step

Inspect the post-T517 `ToolCallRepromptStage` shape before choosing T518. Do not assume another extraction until the remaining branch ownership is rechecked from current source.
