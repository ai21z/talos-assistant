# [T546-done-high] Extract Tool Outcome Failure Shape Classifier

Status: done
Priority: high
Date: 2026-05-27
Branch: `T546`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `67a7eede`
Predecessor: `T545`

## Scope

T546 extracts only `ToolOutcome` failure-shape classification out of the nested
`ToolCallLoop.ToolOutcome` value.

It intentionally does not move `ToolOutcome`, `LoopResult`, mutation outcome
rendering, retry policy, trace rendering, or final-answer wording.

## Changes

- Added `dev.talos.runtime.toolcall.ToolOutcomeFailureShape`.
- Moved the string/error-code classification bodies for:
  - invalid empty edit arguments;
  - full-rewrite repair redirects;
  - old-string-not-found edit failures;
  - append-line preservation failures;
  - expected-target scope failures.
- Kept the existing `ToolOutcome` instance methods as compatibility wrappers.
- Added a RED/GREEN ownership test proving the classification bodies no longer
  live in `ToolCallLoop.java`.

## TDD Evidence

RED command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest.toolOutcomeFailureShapePredicatesDelegateToOwner" --no-daemon
```

RED result:

```text
ToolOutcomeFactoryTest > toolOutcomeFailureShapePredicatesDelegateToOwner() FAILED
AssertionFailedError at ToolOutcomeFactoryTest.java:146
```

Failure reason: `ToolOutcomeFailureShape.java` did not exist and
`ToolCallLoop.java` still owned the predicate bodies.

GREEN command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest.toolOutcomeFailureShapePredicatesDelegateToOwner" --no-daemon
```

GREEN result: passed.

Focused regression command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --tests "dev.talos.runtime.outcome.MutationOutcomeTest" --tests "dev.talos.runtime.outcome.MutationFailureAnswerRendererTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetScopeRepairPlannerTest" --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
```

Focused regression result: passed.

## Ownership Decision

`ToolOutcomeFailureShape` belongs to `dev.talos.runtime.toolcall`.

Reason:

- it classifies failure shapes from `ToolOutcome` execution facts;
- it is not final-answer rendering;
- it is not verification policy;
- it is not retry orchestration;
- it supports multiple consumers while preserving the current `ToolOutcome`
  compatibility surface.

## Preserved Behavior

The following public `ToolOutcome` methods remain available and delegate to the
new owner:

- `invalidEmptyEditArguments()`;
- `fullRewriteRepairRedirect()`;
- `oldStringNotFoundEditFailure()`;
- `appendLinePreservationFailure()`;
- `expectedTargetScopeFailure()`.

No wording, status classification, repair decision, or final-answer behavior
changed.

## Rejected Scope

### Move `ToolOutcome`

Rejected.

Reason: `ToolOutcome` still has broad consumer spread and requires a separate
compatibility plan.

### Move `LoopResult`

Rejected.

Reason: `LoopResult` remains the public loop result facade.

### Change consumers to call `ToolOutcomeFailureShape` directly

Rejected.

Reason: this ticket is an ownership extraction, not an API migration. Keeping
the wrappers avoids broad consumer churn and preserves compatibility.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest.toolOutcomeFailureShapePredicatesDelegateToOwner" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --tests "dev.talos.runtime.outcome.MutationOutcomeTest" --tests "dev.talos.runtime.outcome.MutationFailureAnswerRendererTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetScopeRepairPlannerTest" --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T546 merges, inspect the remaining `ToolOutcome` and `LoopResult`
compatibility surface before choosing another implementation. Do not move either
value mechanically.
