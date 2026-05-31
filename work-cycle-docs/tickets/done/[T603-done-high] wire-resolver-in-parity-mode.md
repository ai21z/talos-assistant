# [T603] Wire resolver in parity mode

## Summary

T603 routes `TaskContractResolver.fromUserRequest(...)` through the roleful
intent compatibility path:

```text
legacy TaskContract -> TaskIntentResolver -> TaskContractCompiler -> TaskContract
```

The old resolver logic remains intact as a package-private legacy seam:

```text
TaskContractResolver.resolveLegacyFromUserRequest(...)
```

No behavior-changing role assignment starts in this ticket. No live-audit
failure is fixed by T603.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = cfc1461e
talosVersion = 0.9.9
```

Predecessor:

```text
T602 = Add TaskIntent and compatibility compiler
```

## What Changed

Added:

```text
src/main/java/dev/talos/runtime/intent/TaskIntentResolver.java
src/test/java/dev/talos/runtime/task/TaskIntentResolverParityTest.java
```

Changed:

```text
src/main/java/dev/talos/runtime/task/TaskContractResolver.java
```

`TaskIntentResolver` currently performs a parity conversion from an existing
legacy `TaskContract` into a roleful `TaskIntent`:

- legacy `expectedTargets` -> `MUST_MUTATE`;
- legacy `sourceEvidenceTargets` -> `SOURCE_EVIDENCE`;
- legacy `forbiddenTargets` -> `FORBIDDEN`;
- scalar task fields are preserved exactly.

This mapping is intentionally not the final target-role semantics. It is the
compatibility bridge needed before T604/T605 can begin behavior-changing role
assignment.

## Tests Added

```text
src/test/java/dev/talos/runtime/task/TaskIntentResolverParityTest.java
```

Coverage:

- representative edit/create/source/forbidden/read-only/static-web prompts;
- blank request handling;
- projected contracts match legacy contracts field-for-field;
- public `TaskContractResolver.fromUserRequest(...)` matches the same legacy
  result after routing through the roleful path.

## RED/GREEN Evidence

RED observed before production code:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskIntentResolverParityTest" --no-daemon
```

Expected failure:

```text
:compileTestJava FAILED
cannot find symbol: class TaskIntentResolver
cannot find symbol: method resolveLegacyFromUserRequest(String)
```

GREEN after adding parity resolver and legacy seam:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskIntentResolverParityTest" --no-daemon
BUILD SUCCESSFUL
```

Neighboring focused suites:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.*" --no-daemon
BUILD SUCCESSFUL

.\gradlew.bat test --tests "dev.talos.runtime.intent.*" --no-daemon
BUILD SUCCESSFUL
```

## Behavior Status

Preserved:

- existing `TaskContractResolverTest` behavior;
- existing prompt-debug/trace-visible legacy `TaskContract` fields;
- current static-web conventional target behavior;
- current read-only and mutation classification behavior.

Not fixed yet:

- scoped `do not create extra files` negation;
- `so index.html still works` constraint mention role;
- `script.js`/`scripts.js` workspace reconciliation;
- static-web continuation naming.

## Out Of Scope

- No clause segmentation.
- No new role assignment semantics.
- No workspace target reconciliation.
- No expected-target accounting changes.
- No trace schema changes.
- No prompt-debug schema changes.
- No live-audit behavior change.

## Next Move

```text
[T604] Fix scoped negation failure A
```

T604 should write the failing behavior test first for:

```text
Improve only styles.css. Do not create extra files. Do not modify index.html or scripts.js.
```

The desired result is a mutating contract with `styles.css` as the required
mutation target and `index.html` / `scripts.js` as forbidden targets, while
true global read-only prompts remain read-only.

## Confidence

High. The roleful path is now wired, but the compatibility projection is tested
against the legacy resolver output before any behavior-changing intent logic is
introduced.
