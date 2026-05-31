# [T605] Fix constraint mention failure B

## Summary

T605 fixes the second confirmed roleful-intent live-audit failure:

```text
Rewrite styles.css so index.html still works.
```

Before this ticket, flat target extraction projected both `styles.css` and
`index.html` into `TaskContract.expectedTargets`. That made
`ExpectedTargetProgressAccounting` treat the verification constraint target as a
required mutation target, so a successful `styles.css` rewrite could still fall
through as incomplete or blocked because `index.html` was not mutated.

After this ticket:

```text
styles.css = MUST_MUTATE
index.html = VERIFY_ONLY
TaskContract.expectedTargets = [styles.css]
```

The compatibility projection still exposes only legacy `TaskContract` fields,
but `VERIFY_ONLY` targets no longer enter expected mutation progress.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 312f603e
talosVersion = 0.9.9
```

Predecessor:

```text
T604 = Fix scoped negation failure A
```

## What Changed

Changed:

```text
src/main/java/dev/talos/runtime/intent/TaskIntentResolver.java
src/test/java/dev/talos/runtime/task/TaskIntentResolverTest.java
src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java
src/test/java/dev/talos/runtime/toolcall/ExpectedTargetProgressAccountingTest.java
src/test/java/dev/talos/runtime/toolcall/ToolRepromptSuccessfulMutationDecisionTest.java
```

`TaskIntentResolver` now segments constraint phrases so that targets in these
purpose/compatibility clauses are assigned `VERIFY_ONLY` instead of
`MUST_MUTATE`:

- `so <target> still works`;
- `without breaking <target>`;
- `without changing <target>`;
- `compatible with <target>`;
- `stay compatible with <target>`;
- `stays compatible with <target>`.

Mutation target extraction now considers only the action side of the clause.
For example, in `Rewrite styles.css so index.html still works`, the mutation
fragment is `Rewrite styles.css`, while the constraint fragment is
`so index.html still works`.

## Tests Added

```text
TaskIntentResolverTest.rolefulIntentTreatsConstraintTargetsAsVerifyOnly
TaskContractResolverTest.constraintMentionDoesNotBecomeExpectedMutationTarget
ExpectedTargetProgressAccountingTest.verifyOnlyConstraintTargetDoesNotRemainAsMutationProgressTarget
ToolRepromptSuccessfulMutationDecisionTest.successfulMutationOfMustTargetDoesNotBlockOnVerifyOnlyConstraintTarget
```

Coverage:

- roleful resolver assigns `styles.css = MUST_MUTATE`;
- roleful resolver assigns `index.html = VERIFY_ONLY`;
- compatibility projection excludes `VERIFY_ONLY` from `expectedTargets`;
- expected-target progress accounting is satisfied by mutating `styles.css`;
- successful mutation handling does not fall through just because the
  verification target was not mutated.

## RED/GREEN Evidence

RED observed before production code:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskIntentResolverTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --tests "dev.talos.runtime.toolcall.ToolRepromptSuccessfulMutationDecisionTest" --no-daemon
```

Expected failures:

```text
TaskContractResolverTest > constraintMentionDoesNotBecomeExpectedMutationTarget FAILED
TaskIntentResolverTest > rolefulIntentTreatsConstraintTargetAsVerifyOnly FAILED
ExpectedTargetProgressAccountingTest > verifyOnlyConstraintTargetDoesNotRemainAsMutationProgressTarget FAILED
ToolRepromptSuccessfulMutationDecisionTest > successfulMutationOfMustTargetDoesNotBlockOnVerifyOnlyConstraintTarget FAILED
```

GREEN after roleful constraint assignment:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskIntentResolverTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --tests "dev.talos.runtime.toolcall.ToolRepromptSuccessfulMutationDecisionTest" --no-daemon
BUILD SUCCESSFUL
```

## Behavior Status

Fixed in this ticket:

- Failure B: constraint mentions no longer become required mutation targets;
- successful mutation of the must-mutate target is no longer rendered blocked
  only because a verify-only target was not changed.

Preserved:

- legacy `TaskContract` compatibility shape;
- existing T604 scoped-negation behavior;
- true read-only/advisory behavior;
- source-evidence and forbidden target projection.

Not fixed yet:

- workspace target reconciliation for `script.js`/`scripts.js`;
- static-web continuation naming;
- roleful trace/prompt-debug evidence;
- deterministic E2E regression pack.

## Next Move

```text
[T606] Add workspace target reconciliation
```
