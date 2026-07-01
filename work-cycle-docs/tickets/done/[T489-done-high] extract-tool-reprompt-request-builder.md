# [T489-done-high] Extract Tool Reprompt Request Builder

## Status

Done.

## Scope

T489 extracts reprompt request assembly from `ToolCallRepromptStage` into
`ToolRepromptRequestBuilder`.

This ticket does not change continuation policy, approval-denied behavior,
policy-denied behavior, static-web repair planning, expected-target repair
planning, source-evidence repair planning, append-line/old-string repair
planning, context-budget fallback behavior, compact mutation continuation,
LLM invocation, engine exception handling, trace wording, prompt wording, or
final-answer behavior.

## What Changed

- Added `dev.talos.runtime.toolcall.ToolRepromptRequestBuilder`.
- `ToolCallRepromptStage` now delegates:
  - current native tool-spec lookup;
  - static-repair tool narrowing;
  - expected-target tool narrowing;
  - static-repair compact reprompt message construction;
  - static repair context enrichment;
  - pending-obligation request controls.
- `ToolCallRepromptStage.java` moved from 1007 lines to 884 lines.

## Behavior Preservation Notes

The builder preserves the existing controls behavior exactly:

- required-tool-choice controls are emitted only when a pending action
  obligation is active;
- the current LLM reports support for required tool choice;
- `state.ctx.nativeToolSpecs()` contains a mutating tool;
- debug tags still start with `pending-action-obligation` and append the
  non-blank requested tag when different.

The builder still allows request tool-spec lookup to fall back from
`state.ctx.nativeToolSpecs()` to `state.ctx.llm().getToolSpecs()`, matching the
old `currentNativeToolSpecs(...)` helper behavior for reprompt tool lists.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptRequestBuilderTest" --no-daemon
```

failed before implementation because `ToolRepromptRequestBuilder` did not
exist.

Focused GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptRequestBuilderTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --tests "dev.talos.core.llm.ToolCallRepromptStagePromptDebugTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --tests "dev.talos.runtime.ToolCallLoopTest.*static*" --no-daemon
```

Full ticket gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T489 `ToolCallRepromptStage` shape before choosing T490.
The next candidate should not be assumed. Likely candidates are:

- context-budget continuation handling;
- failure-policy stop rendering;
- pending action-obligation/progress selection;
- or a short closeout/retarget decision if the remaining stage is no longer a
  good extraction lane.
