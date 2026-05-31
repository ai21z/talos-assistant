# [T604] Fix scoped negation failure A

## Summary

T604 fixes the first confirmed roleful-intent live-audit failure:

```text
Improve only styles.css. Do not create extra files. Do not modify index.html or scripts.js.
```

Before this ticket, the lexical intent path treated `do not create` as a
global read-only negation before considering the explicit `Improve only
styles.css` mutation directive. Talos therefore hid mutation tools for a valid
file-edit request.

After this ticket, roleful intent assignment treats `do not create extra files`
as a scoped output constraint only when paired with an explicit mutation clause.
The compatibility `TaskContract` projection is:

```text
type = FILE_EDIT
mutationRequested = true
mutationAllowed = true
expectedTargets = [styles.css]
forbiddenTargets = [index.html, scripts.js]
```

True read-only prompts such as `Review files. Do not create files.` remain
non-mutating.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 88758903
talosVersion = 0.9.9
```

Predecessor:

```text
T603 = Wire resolver in parity mode
```

## What Changed

Changed:

```text
src/main/java/dev/talos/runtime/task/TaskContractResolver.java
src/main/java/dev/talos/runtime/intent/TaskIntentResolver.java
```

Added:

```text
src/test/java/dev/talos/runtime/task/TaskIntentResolverTest.java
```

Updated:

```text
src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java
src/test/java/dev/talos/runtime/toolcall/ToolSurfacePlannerTest.java
```

`TaskContractResolver.fromUserRequest(...)` now routes through
`TaskIntentResolver.fromUserRequest(userRequest, legacyContract)`.

The new roleful path remains narrow:

- starts from the legacy contract;
- only overrides `global-read-only-negation` when the prompt has an explicit
  mutation target and a scoped `extra files` creation constraint;
- assigns explicit mutation targets as `MUST_MUTATE`;
- assigns named negated targets from segmented clauses as `FORBIDDEN`;
- preserves source-evidence targets from the legacy contract;
- leaves all other prompts on the parity path.

This is not a one-off addition of `extra files` to the old
`MutationIntent.isScopedLimiter(...)` tail list. The behavior is handled behind
the roleful resolver, with clause segmentation preserving filenames such as
`styles.css`.

## Tests Added

```text
TaskIntentResolverTest.rolefulIntentTreatsExtraFilesAsScopedOutputConstraint
TaskContractResolverTest.scopedExtraFileCreationConstraintDoesNotSuppressExplicitStyleMutation
TaskContractResolverTest.reviewDoNotCreateFilesRemainsReadOnly
ToolSurfacePlannerTest.scopedExtraFileCreationConstraintKeepsFileEditToolsVisible
```

Coverage:

- scoped `extra files` constraint no longer cancels explicit mutation;
- `styles.css` is the only expected mutation target;
- `index.html` and `scripts.js` are forbidden targets;
- mutating write/edit tools are visible for the APPLY phase;
- a true read-only `Review files. Do not create files.` prompt remains
  non-mutating.

## RED/GREEN Evidence

RED observed before production code:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskIntentResolverTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon
```

Expected failure:

```text
:compileTestJava FAILED
cannot find symbol: method fromUserRequest(String,TaskContract)
```

Intermediate failure after adding the method exposed a real segmentation issue:
splitting on every period broke `styles.css`. The splitter now segments on
sentence-boundary whitespace instead of file-extension dots.

GREEN:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskIntentResolverTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon
BUILD SUCCESSFUL
```

## Behavior Status

Fixed in this ticket:

- Failure A: scoped `do not create extra files` no longer hides mutation tools
  when the same request explicitly mutates a named file.

Preserved:

- true global read-only prompts;
- existing legacy `TaskContract` projection shape;
- source-evidence target projection;
- prompt-debug/trace-visible legacy contract fields.

Not fixed yet:

- constraint mentions such as `so index.html still works`;
- expected-target progress accounting for `VERIFY_ONLY` targets;
- workspace target reconciliation for `script.js`/`scripts.js`;
- static-web continuation naming.

## Next Move

```text
[T605] Fix constraint mention failure B
```
