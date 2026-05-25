# [T453-done-high] Static Web Continuation Boundary Decision

## Status

Done.

## Scope

T453 inspects the static-web continuation cluster selected by T452.

This is a no-code decision ticket. It does not change runtime behavior, prompt
wording, tool selection, verifier behavior, pending action obligations, failure
dominance, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `c1dd6eb2`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ToolCallRepromptStage.java` | 2436 lines |
| Architecture baseline | 0 |

## Source Inventory

The static-web continuation cluster in `ToolCallRepromptStage` currently owns:

- directory-only static web creation continuation;
- static verification failure continuation after a partial successful web
  mutation;
- continuation prompt messages for both cases;
- static verification failure context wording;
- required-tool controls for continuation;
- successful directory mutation summary selection;
- static-web verification continuation eligibility;
- missing CSS/JavaScript/HTML target inference from verifier problems;
- missing linked asset inference from mutated HTML;
- small-web mutation satisfaction accounting.

Existing behavior coverage includes:

- directory-only mutation continues to actual file writes;
- partial `index.html` write continues to linked CSS/JavaScript assets;
- repeated rewrite of already satisfied static-web target is rejected before
  execution when missing targets remain.

## Decision

Static-web continuation is a coherent next implementation lane, but it should
be extracted conservatively.

The owner should live in runtime/toolcall ownership:

```text
dev.talos.runtime.toolcall.StaticWebContinuationPlanner
```

The next implementation ticket should extract a plan-returning owner, not a
backend-calling owner.

Preferred API shape:

```text
StaticWebContinuationPlanner.directoryOnlyPlan(LoopState state, List<ToolSpec> baseTools)
StaticWebContinuationPlanner.verificationFailurePlan(LoopState state, List<ToolSpec> baseTools)
```

or one combined selector:

```text
StaticWebContinuationPlanner.nextPlan(LoopState state, List<ToolSpec> baseTools)
```

The plan should contain:

- request messages;
- narrowed tool specs;
- `ChatRequestControls`;
- retry name/debug label;
- optional missing-target pending obligation detail.

`ToolCallRepromptStage` should keep lifecycle placement:

- decide when static-web continuation is considered in the top-level loop;
- apply pending action obligation if the plan asks for one;
- call the existing `chatReprompt(...)`;
- preserve ordering relative to mutation success, static verification, generic
  failure policy, and repair continuations.

This is safer than moving `chatReprompt(...)` into the new owner because the
current `chatReprompt(...)` path also owns context-budget fallback and loop
state mutation. Moving that call would mix static-web ownership with generic
provider continuation behavior.

## T454 Guardrails

T454 must preserve:

- exact `[StaticWebCreationContinuation]` prompt wording;
- exact `[StaticWebVerificationContinuation]` prompt wording;
- exact static verification failure context wording;
- `static-web-directory-only-continuation` retry name;
- `static-web-verification-continuation` retry name;
- required tool-choice behavior when the backend supports required tools;
- write-file-only narrowing for directory-only continuation when available;
- write/edit narrowing for verification continuation;
- pending expected-target obligation setup for missing static-web targets;
- linked CSS/JavaScript inference from mutated HTML;
- small-web mutation satisfaction accounting;
- rejection of repeated satisfied-target rewrites when missing assets remain.

T454 must not touch:

- compact mutation continuation;
- compact read-only evidence continuation;
- terminal read-only stop answers;
- generic failure policy ordering;
- expected-target, source-evidence, append-line, or old-string repair lanes;
- static verifier problem wording;
- final outcome warning construction;
- `AssistantTurnExecutor` final-answer shaping.

## T454 Test Plan

T454 should start with a focused RED ownership test for the new planner proving
that static-web continuation planning moved out of `ToolCallRepromptStage`.

Focused tests should cover:

- directory-only continuation plan prefers `talos.write_file`;
- verification failure plan carries missing target pending-obligation context;
- linked asset inference includes missing linked CSS/JavaScript from mutated
  HTML;
- already satisfied small-web targets are excluded from missing targets.

Adjacent regression tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.staticWebCreationDirectoryOnlyMutationContinuesToFileWrites" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebCreationMissingLinkedAssetsContinuesAfterIndexWrite" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebCreationMissingAssetContinuationRejectsRepeatedSatisfiedTargetRewrite" --no-daemon
```

Full gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
