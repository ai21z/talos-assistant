# [T389-done-high] Extract Mutation Target Readback Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T389`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `4a4a7925`
Predecessor: `T388`

## Scope

T389 implements the post-T388 inspection result:

```text
[T389] Extract mutation target readback verifier
```

This is a behavior-preserving ownership extraction. It moves generic
post-mutation target accounting and readback out of `StaticTaskVerifier` and
into:

```text
src/main/java/dev/talos/runtime/verification/MutationTargetReadbackVerifier.java
```

The public `StaticTaskVerifier.verify(...)` facade remains the orchestrator.

## Source Inspection

The remaining `StaticTaskVerifier` responsibilities were inspected before
choosing the T389 implementation unit:

- Final summary selection still depends on every verifier result and should
  remain orchestration for now.
- Exact edit replacement verification is coherent but depends on edit evidence
  and all-mutation coverage semantics.
- Expected target verification mixes task contracts, static-web context target
  exemptions, aliases, similar-target detection, and only-target wording.
- Mutation target readback is the clean lower-level owner: it only classifies
  successful mutating outcomes into direct file targets or workspace operation
  plans, then verifies target readability, placeholder status, and file-level
  verification status.

## Implementation

`MutationTargetReadbackVerifier` now owns:

- direct successful mutation target path normalization;
- missing target path problem reporting;
- mutated target existence/readability checks;
- blank target content checks;
- template placeholder checks;
- file-level verification status checks;
- direct mutation target collection for later task-specific verification;
- workspace operation plan collection for `WorkspaceOperationStaticVerifier`.

`StaticTaskVerifier` now:

- delegates mutation target readback to `MutationTargetReadbackVerifier`;
- appends returned facts and problems;
- uses returned mutation targets for capability/profile and expected-target
  checks;
- passes returned workspace operation plans to `WorkspaceOperationStaticVerifier`;
- keeps final status and summary selection unchanged.

## Behavior Preservation

T389 intentionally does not change runtime behavior or outcome wording:

- target/readback pass wording is unchanged;
- placeholder failure wording is unchanged;
- file-level warning failure wording is unchanged;
- exact edit replacement behavior is untouched;
- expected/forbidden target verification is untouched;
- static-web verification is untouched;
- source-derived and task-expectation verification are untouched.

## Measurements

Measured after extraction:

| File | Lines | Role |
|---|---:|---|
| `StaticTaskVerifier.java` | 908 | Public verifier facade/orchestrator plus remaining verifier domains. |
| `MutationTargetReadbackVerifier.java` | 112 | Generic mutation target accounting and readback verifier. |

Before T389, `StaticTaskVerifier.java` was 964 lines on `4a4a7925`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.MutationTargetReadbackVerifierTest" --no-daemon
```

Result: failed at `:compileTestJava` because
`MutationTargetReadbackVerifier` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.MutationTargetReadbackVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

Result: passed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.MutationTargetReadbackVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

- Focused direct/facade tests: passed (`BUILD SUCCESSFUL`).
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`).
- `git diff --check`: passed, line-ending warnings only.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; final
  packet rerun had 14 actionable tasks: 2 executed, 12 up-to-date).

## Next Decision

After T389 lands, do not automatically extract another verifier.

The next inspection should choose between:

- exact edit replacement fallback verification;
- expected/forbidden target verification;
- final outcome summary selection.

Expected-target verification is probably a larger decision than exact-edit
fallback because it mixes task contracts, static-web context, aliases, and
similar-target safety wording.
