# [T601] Add roleful intent value types

## Summary

T601 adds the first inert roleful intent value types under:

```text
dev.talos.runtime.intent
```

No resolver wiring changed. No production behavior changed. The existing
`TaskContractResolver` and `TaskContract` compatibility surface remain
untouched.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = eeb8ae7f
talosVersion = 0.9.9
```

Predecessor:

```text
T600 = Roleful intent lane decision and test matrix
```

## Added Types

| Type | Purpose |
| --- | --- |
| `TargetRole` | Deterministic target-role enum with strongest-role precedence. |
| `TargetSource` | Origin enum for how a target reference was derived. |
| `IntentDerivation` | Source, reason, source span/text, and confidence for a target reference. |
| `TargetRef` | Normalized target path plus role and derivation. |
| `ArtifactTargetSet` | Immutable target collection that merges duplicate target refs by strongest role. |

## Role Precedence

Duplicate target references preserve the strongest role by this deterministic
precedence:

```text
FORBIDDEN
MUST_MUTATE
OUTPUT_DESTINATION
MUST_READ
SOURCE_EVIDENCE
VERIFY_ONLY
MAY_MUTATE
MENTIONED_ONLY
```

If two references have the same role, the higher-confidence derivation wins.
If both role and confidence tie, the earlier reference is preserved.

## Tests Added

```text
src/test/java/dev/talos/runtime/intent/TargetRoleTest.java
src/test/java/dev/talos/runtime/intent/ArtifactTargetSetTest.java
```

Coverage:

- initial role set and precedence order;
- strongest-role selection;
- path normalization from Windows separators to slash separators;
- preservation of role, source, source span/text, reason, and confidence;
- duplicate target merge by strongest role;
- role-based target filtering;
- invalid blank target and invalid confidence rejection;
- immutable target lists.

## RED/GREEN Evidence

RED observed before production code:

```text
.\gradlew.bat test --tests "dev.talos.runtime.intent.*" --no-daemon
```

Expected failure:

```text
:compileTestJava FAILED
cannot find symbol: class IntentDerivation
cannot find symbol: variable TargetSource
cannot find symbol: class ArtifactTargetSet
cannot find symbol: class TargetRef
cannot find symbol: variable TargetRole
```

GREEN after adding value types:

```text
.\gradlew.bat test --tests "dev.talos.runtime.intent.*" --no-daemon
BUILD SUCCESSFUL
```

## Out Of Scope

- No `TaskIntent` yet.
- No `TaskContractCompiler` yet.
- No `TaskIntentResolver` yet.
- No changes to `TaskContractResolver`.
- No changes to task classification.
- No changes to expected-target projection.
- No trace or prompt-debug changes.
- No live-audit behavior is fixed by T601.

## Next Move

```text
[T602] Add TaskIntent and compatibility compiler
```

T602 should add `TaskIntent` and `TaskContractCompiler`, with projection tests
proving roleful target sets compile into the current `TaskContract` shape:

- `VERIFY_ONLY` excluded from `expectedTargets`;
- `FORBIDDEN` included in `forbiddenTargets`;
- `SOURCE_EVIDENCE` included in `sourceEvidenceTargets`;
- `MUST_MUTATE + OUTPUT_DESTINATION` included in `expectedTargets`;
- existing `TaskContractResolver` behavior unchanged.

## Confidence

High. The ticket adds only inert immutable value types and tests. It does not
wire the new model into runtime behavior.
