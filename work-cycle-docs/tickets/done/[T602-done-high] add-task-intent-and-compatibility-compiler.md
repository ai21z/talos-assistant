# [T602] Add TaskIntent and compatibility compiler

## Summary

T602 adds `TaskIntent` and `TaskContractCompiler` behind the existing
`TaskContract` compatibility surface.

No resolver wiring changed. No task classification changed. No live-audit
failure is fixed by this ticket.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 8be0240f
talosVersion = 0.9.9
```

Predecessor:

```text
T601 = Add roleful intent value types
```

## Added Types

| Type | Purpose |
| --- | --- |
| `TaskIntent` | Roleful internal intent record carrying task type, mutation/verification flags, target roles, original request, and classification reason. |
| `TaskContractCompiler` | Deterministic projection from `TaskIntent` to the current `TaskContract` shape. |

## Projection Rules Covered

| Role | Projection |
| --- | --- |
| `MUST_MUTATE` | `TaskContract.expectedTargets` |
| `OUTPUT_DESTINATION` | `TaskContract.expectedTargets` |
| `SOURCE_EVIDENCE` | `TaskContract.sourceEvidenceTargets` |
| `MUST_READ` | `TaskContract.sourceEvidenceTargets` for the current compatibility projection |
| `FORBIDDEN` | `TaskContract.forbiddenTargets` |
| `VERIFY_ONLY` | No mutation-progress target projection |
| `MAY_MUTATE` | No mutation-progress target projection |
| `MENTIONED_ONLY` | No runtime obligation projection |

Scalar fields preserved:

- `TaskType`;
- `mutationRequested`;
- `mutationAllowed`;
- `verificationRequired`;
- `originalUserRequest`;
- `classificationReason`.

Null defaults:

- null `TaskType` becomes `TaskType.UNKNOWN`;
- null `ArtifactTargetSet` becomes `ArtifactTargetSet.empty()`;
- null request/reason strings become empty strings;
- null `TaskIntent` compiles to `TaskContract.unknown("")`.

## Tests Added

```text
src/test/java/dev/talos/runtime/intent/TaskContractCompilerTest.java
```

Coverage:

- `MUST_MUTATE + OUTPUT_DESTINATION` project to `expectedTargets`;
- `VERIFY_ONLY`, `MAY_MUTATE`, and `MENTIONED_ONLY` do not enter
  `expectedTargets`;
- `SOURCE_EVIDENCE + MUST_READ` project to `sourceEvidenceTargets`;
- `FORBIDDEN` projects to `forbiddenTargets`;
- null field defaults are stable;
- null intent compiles to unknown contract;
- existing `TaskContractResolver` behavior remains unchanged for the current
  conventional static-web target case.

## RED/GREEN Evidence

RED observed before production code:

```text
.\gradlew.bat test --tests "dev.talos.runtime.intent.TaskContractCompilerTest" --no-daemon
```

Expected failure:

```text
:compileTestJava FAILED
cannot find symbol: class TaskIntent
cannot find symbol: variable TaskContractCompiler
```

GREEN after adding production code:

```text
.\gradlew.bat test --tests "dev.talos.runtime.intent.TaskContractCompilerTest" --no-daemon
BUILD SUCCESSFUL
```

Neighboring focused package check:

```text
.\gradlew.bat test --tests "dev.talos.runtime.intent.*" --no-daemon
BUILD SUCCESSFUL
```

## Out Of Scope

- No `TaskIntentResolver` yet.
- No `TaskContractResolver` delegation yet.
- No classification changes.
- No expected-target extraction changes.
- No workspace target reconciliation.
- No trace or prompt-debug schema changes.
- No live-audit behavior is fixed.

## Next Move

```text
[T603] Wire resolver in parity mode
```

T603 should introduce `TaskIntentResolver` behind `TaskContractResolver` while
preserving existing `TaskContract` behavior. The ticket should compare legacy
resolver output to roleful projection for representative existing prompts
before any behavior-changing role assignment starts in T604.

## Confidence

High. The ticket adds a deterministic compatibility projection with focused
tests and leaves the live resolver path unchanged.
