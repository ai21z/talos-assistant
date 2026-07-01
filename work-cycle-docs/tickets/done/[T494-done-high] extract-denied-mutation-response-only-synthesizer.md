# [T494-done-high] Extract Denied-Mutation Response-Only Synthesizer

## Status

Done.

## Scope

T494 extracts policy-denied mutation response-only synthesis from
`ToolCallRepromptStage` into `DeniedMutationResponseOnlySynthesizer`.

This ticket does not change approval-denied behavior, tool execution, approval
policy, protected-read behavior, failure-policy stop rendering, repair
planning, context-budget fallback behavior, trace wording, prompt wording,
outcome wording, or final-answer behavior.

## What Changed

- Added `dev.talos.runtime.toolcall.DeniedMutationResponseOnlySynthesizer`.
- `ToolCallRepromptStage` now delegates only the non-approval
  `mutatingDeniedThisIteration()` terminal answer path.
- The explicit user approval-denial path still stops deterministically inside
  `ToolCallRepromptStage`.
- Removed `responseOnlyAfterDeniedMutation(...)` and
  `deniedMutationStopMessage()` from `ToolCallRepromptStage`.
- `ToolCallRepromptStage.java` moved from 658 lines to 619 lines.

## Behavior Preservation Notes

The extracted owner preserves existing behavior:

- returns the deterministic policy stop message when no LLM is available;
- appends the same temporary `[Tool policy stop]` instruction;
- uses `state.ctx.llm().chatFull(state.messages, state.ctx.nativeToolSpecs())`;
- rejects returned native tool calls;
- strips textual tool-call blocks before accepting text;
- rejects blank text and textual tool-call debris;
- falls back to the same deterministic stop message on exception;
- removes the temporary policy-stop prompt in `finally`.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.DeniedMutationResponseOnlySynthesizerTest" --no-daemon
```

failed before implementation because `DeniedMutationResponseOnlySynthesizer`
did not exist.

Focused GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.DeniedMutationResponseOnlySynthesizerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.deniedMutationStopsWithoutReprompting" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.*deniedMutation*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.*deniedMutation*" --tests "dev.talos.runtime.policy.ActionObligationFailureAssessmentTest.*deniedMutation*" --tests "dev.talos.runtime.outcome.MutationFailureAnswerRendererTest.*deniedMutation*" --no-daemon
```

Full ticket gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

After T494 merges, inspect the post-T494 `ToolCallRepromptStage` shape before
choosing T495. Do not assume another extraction; likely remaining candidates
are failure-policy stop rendering, repair-budget predicates, or a closeout
decision for the current reprompt-stage lane.
