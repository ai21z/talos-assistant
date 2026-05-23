# [T392-done-high] Extract Target Scope Static Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T392`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `4e0adf41`
Predecessor: `T391`

## Scope

T392 implements the T391 decision:

```text
[T392] Extract target scope static verifier
```

This is a behavior-preserving ownership extraction. It moves target-scope
post-apply verification out of `StaticTaskVerifier` and into:

```text
src/main/java/dev/talos/runtime/verification/TargetScopeStaticVerifier.java
```

`StaticTaskVerifier` remains the public verification facade and still owns
final `TaskVerificationResult` summary selection.

## Implementation

`TargetScopeStaticVerifier` now owns:

- expected mutation target checks;
- forbidden mutation target checks;
- only-target request guard checks;
- workspace-operation target exemptions;
- workspace-operation target aliases;
- static-web repair context target satisfaction;
- Windows-aware path matching;
- `script.js` versus `scripts.js` similar-target diagnostics;
- target-scope facts and problems.

`StaticTaskVerifier` now:

- delegates target-scope verification to `TargetScopeStaticVerifier`;
- appends returned facts and problems;
- keeps capability-profile selection, expectation verification, exact-edit
  verification, source-derived verification, static-web verification, and final
  summary precedence unchanged.

## Behavior Preservation

T392 intentionally does not change runtime behavior or outcome wording:

- expected-target miss wording remains `expected target was not successfully
  mutated`;
- forbidden-target wording remains `forbidden mutation target was changed`;
- only-target wording remains `non-requested mutation target was changed under
  an only-target request`;
- similar-target diagnostic wording remains unchanged;
- expected-target success facts remain unchanged;
- static-web context-target satisfaction remains unchanged;
- Windows case-insensitive expected-target matching remains unchanged.

## Measurements

Measured after extraction:

| File | Lines | Role |
|---|---:|---|
| `StaticTaskVerifier.java` | 641 | Public verifier facade/orchestrator plus remaining verifier domains. |
| `TargetScopeStaticVerifier.java` | 238 | Target-scope verifier. |

Before T392, `StaticTaskVerifier.java` was 835 lines on `4e0adf41`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TargetScopeStaticVerifierTest" --no-daemon
```

Result: failed at `:compileTestJava` because `TargetScopeStaticVerifier` did
not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TargetScopeStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --no-daemon
```

Result: passed (`BUILD SUCCESSFUL`; 6 actionable tasks: 4 executed, 2
up-to-date).

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TargetScopeStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

- Focused target-scope/facade/workspace-operation tests: passed (`BUILD
  SUCCESSFUL`; 6 actionable tasks: 4 executed, 2 up-to-date).
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `git diff --check`: passed, line-ending warnings only.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; first run
  had 14 actionable tasks: 8 executed, 6 up-to-date; final packet rerun had
  14 actionable tasks: 2 executed, 12 up-to-date).

## Next Decision

After T392 lands, do not automatically extract another utility from
`StaticTaskVerifier`.

The next ticket should inspect the remaining responsibilities after the
target-scope extraction. Likely candidates are final summary selection or the
remaining static-web orchestration methods, but source inspection should choose
the next owner.
