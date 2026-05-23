# [T390-done-high] Extract Exact Edit Replacement Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T390`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `fa4048a1`
Predecessor: `T389`

## Scope

T390 implements the post-T389 inspection result:

```text
[T390] Extract exact edit replacement verifier
```

This is a behavior-preserving ownership extraction. It moves exact edit
replacement fallback verification out of `StaticTaskVerifier` and into:

```text
src/main/java/dev/talos/runtime/verification/ExactEditReplacementVerifier.java
```

The public `StaticTaskVerifier.verify(...)` facade remains the orchestrator and
still owns final status/summary selection.

## Source Inspection

Before implementation, T390 compared exact-edit fallback verification with
expected-target verification:

- Exact-edit fallback was coherent: it only checks edit-tool mutation evidence,
  target readback, old/new replacement observation, and whether all successful
  mutations are covered by exact edit evidence.
- Expected-target verification was not selected because it mixes task-contract
  scope, forbidden target detection, expected target aliases, static-web context
  target exemptions, Windows case matching, singular/plural similar-target
  safety, and only-target request wording.
- Final summary selection remains orchestration and should not be extracted
  while it still orders multiple verifier domains.

## Implementation

`ExactEditReplacementVerifier` now owns:

- filtering successful exact edit outcomes;
- target path normalization for exact edit evidence;
- exact edit target readability checks;
- replacement new-text observation;
- replacement old-text absence checks;
- exact edit facts/problems;
- the `coversAllSuccessfulMutations` guard that prevents mixed exact-edit and
  readback-only mutations from overclaiming a passed exact-edit verification.

`StaticTaskVerifier` now:

- delegates exact-edit fallback checks to `ExactEditReplacementVerifier`;
- appends returned facts and problems;
- uses returned booleans for existing exact-edit failure/pass summary branches;
- keeps expected-target verification and final summary precedence unchanged.

## Behavior Preservation

T390 intentionally does not change runtime behavior or outcome wording:

- exact edit pass summary remains `Exact edit replacement verification passed.`;
- exact edit failure summary remains `Exact edit replacement verification failed.`;
- exact edit fact/problem wording is preserved;
- mixed exact edit plus readback-only mutation still falls back to
  target/readback wording;
- expected/forbidden target verification is untouched;
- static-web, source-derived, task-expectation, workspace-operation, and
  mutation-readback verification are untouched.

## Measurements

Measured after extraction:

| File | Lines | Role |
|---|---:|---|
| `StaticTaskVerifier.java` | 835 | Public verifier facade/orchestrator plus remaining verifier domains. |
| `ExactEditReplacementVerifier.java` | 112 | Exact edit replacement fallback verifier. |

Before T390, `StaticTaskVerifier.java` was 908 lines on `fa4048a1`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --no-daemon
```

Result: failed at `:compileTestJava` because
`ExactEditReplacementVerifier` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

Result: passed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
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

After T390 lands, do not automatically extract expected-target verification.

Expected-target verification is likely the next major decision area, not a
cheap mechanical move. It crosses task-contract ownership, static-web context
exceptions, alias handling, similar-target safety, OS-specific path matching,
and only-target request policy.
