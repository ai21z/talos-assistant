# [T608-done-high] Add roleful trace and prompt-debug evidence

## Status

Done.

## Scope

Added evidence-only visibility for roleful target intent while preserving the existing flat `TaskContract` compatibility projection.

This ticket is the renumbered form of the roleful intent lane's planned T584.

## Problem

The runtime could now distinguish roleful targets internally, but trace and prompt-debug evidence still exposed only the legacy flat projection:

- `expectedTargets`
- `forbiddenTargets`
- task type / phase / tool surface

That made it hard to audit whether a target was a mutation obligation, verification-only evidence, or a scoped forbidden target.

## Change

- Added roleful target entries to `TurnPolicyTrace`.
- Persisted roleful target entries in per-turn session JSON while keeping old turn logs readable.
- Added roleful target entries to `LocalTurnTrace.TaskContractSummary` while keeping old local trace JSON readable.
- Added prompt-debug rendering for target roles.
- Added `TaskContractResolver.intentFromUserRequest(...)` and `intentFromMessages(...)` as read-only evidence helpers.

## Compatibility

Existing fields remain intact:

- `expectedTargets`
- `forbiddenTargets`
- `classificationReason`
- `nativeTools`
- `promptTools`

Existing artifacts without `rolefulTargets` still load with an empty roleful-target list.

## Tests

Added or updated:

- `LocalTurnTracePolicyTraceTest.recordsRolefulTargetEvidenceWhilePreservingLegacyProjection`
- `PromptDebugInspectorTargetRolesTest.promptDebugShowsRolefulTargets`
- `JsonSessionStoreTurnsTest.policyTraceRolefulTargetsRoundTrip`
- `JsonSessionStoreTurnsTest.legacyPolicyTraceWithoutRolefulTargetsStillLoads`
- `JsonSessionStoreTurnsTest.legacyLocalTraceWithoutRolefulTargetsStillLoads`

## Verification

RED observed before production change:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePolicyTraceTest.recordsRolefulTargetEvidenceWhilePreservingLegacyProjection" --tests "dev.talos.cli.prompt.PromptDebugInspectorTargetRolesTest.promptDebugShowsRolefulTargets" --tests "dev.talos.runtime.JsonSessionStoreTurnsTest.policyTraceRolefulTargetsRoundTrip" --tests "dev.talos.runtime.JsonSessionStoreTurnsTest.legacyPolicyTraceWithoutRolefulTargetsStillLoads" --tests "dev.talos.runtime.JsonSessionStoreTurnsTest.legacyLocalTraceWithoutRolefulTargetsStillLoads" --no-daemon
```

Failed at compile time because trace/session task-contract summaries had no roleful target evidence surface.

GREEN after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePolicyTraceTest.recordsRolefulTargetEvidenceWhilePreservingLegacyProjection" --tests "dev.talos.cli.prompt.PromptDebugInspectorTargetRolesTest.promptDebugShowsRolefulTargets" --tests "dev.talos.runtime.JsonSessionStoreTurnsTest.policyTraceRolefulTargetsRoundTrip" --tests "dev.talos.runtime.JsonSessionStoreTurnsTest.legacyPolicyTraceWithoutRolefulTargetsStillLoads" --tests "dev.talos.runtime.JsonSessionStoreTurnsTest.legacyLocalTraceWithoutRolefulTargetsStillLoads" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.trace.*" --tests "dev.talos.cli.prompt.*" --tests "dev.talos.runtime.JsonSessionStoreTurnsTest" --no-daemon
```

## Non-goals

- Did not change mutation authority.
- Did not change task classification.
- Did not change tool-surface selection.
- Did not introduce an LLM intent advisor.
- Did not run a live model audit.
