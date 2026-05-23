# [T394-done-high] Extract Task Verification Outcome Selector

Status: done
Priority: high
Date: 2026-05-24
Branch: `T394`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `489124e5`
Predecessor: `T393`

## Scope

T394 implements the T393 decision:

```text
[T394] Extract task verification outcome selector
```

This is a behavior-preserving ownership extraction. It moves final
`TaskVerificationResult` status and summary selection out of
`StaticTaskVerifier` and into:

```text
src/main/java/dev/talos/runtime/verification/TaskVerificationOutcomeSelector.java
```

T394 does not move another random piece from `StaticTaskVerifier`.
Static-web diagnostics remain in place.

## Implementation

`TaskVerificationOutcomeSelector` now owns:

- failure summary precedence;
- passed/readback-only summary precedence;
- outcome-only problem classifiers:
  - exact content;
  - append-line;
  - replacement;
  - bullet-count;
- generic first-problem summary fallback.

`StaticTaskVerifier` now:

- still orchestrates all verifier components;
- still owns capability profile selection;
- still owns static-web verification orchestration;
- still owns public static-web diagnostic facade methods;
- delegates only final outcome selection to `TaskVerificationOutcomeSelector`.

## Behavior Preservation

T394 intentionally does not change runtime behavior or outcome wording.

Preserved summaries include:

- `Source-derived artifact verification failed.`
- `Exact edit replacement verification failed.`
- `Replacement verification failed.`
- `Append line verification failed.`
- `Bullet count verification failed.`
- `Exact content verification failed.`
- `Replacement verification passed.`
- `Append line verification passed.`
- `Bullet count verification passed.`
- `Exact content verification passed.`
- `Exact edit replacement verification passed.`
- `Source-derived artifact verification passed.`
- `Static web coherence checks passed for N mutated target(s).`
- `Target/readback checks passed for N mutated target(s); no task-specific static verifier was applicable.`

The generic fallback summary still joins up to the first three problems and
truncates at 220 characters, matching the prior `StaticTaskVerifier`
implementation.

## Measurements

Measured after extraction:

| File | Lines | Role |
|---|---:|---|
| `StaticTaskVerifier.java` | 621 | Public verifier facade/orchestrator plus static-web verification and diagnostic surfaces. |
| `TaskVerificationOutcomeSelector.java` | 120 | Final task verification outcome/status/summary selector. |

Before T394, `StaticTaskVerifier.java` was 702 lines on `489124e5`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --no-daemon
```

Result: failed at `:compileTestJava` because
`TaskVerificationOutcomeSelector` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --no-daemon
```

Result: passed (`BUILD SUCCESSFUL`; 6 actionable tasks: 1 executed, 5
up-to-date).

Focused behavior preservation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --tests "dev.talos.runtime.verification.SourceDerivedArtifactVerifierTest" --no-daemon
```

Result: passed (`BUILD SUCCESSFUL`; 6 actionable tasks: 1 executed, 5
up-to-date).

One earlier parallel local focused-test invocation collided on Gradle's
`build/test-results/test/binary/output.bin` cleanup path. The same selector
test was rerun serially and passed, so that collision is not treated as a test
failure for the implementation.

## Non-Goals

T394 does not:

- move static-web diagnostics;
- move `verifySmallWebWorkspace(...)`;
- change static-web verification policy;
- change expectation verification;
- change exact edit verification;
- change source-derived artifact verification;
- change target-scope verification;
- change final outcome wording.

## Acceptance Criteria

- `TaskVerificationOutcomeSelector` is the only new production owner.
- `StaticTaskVerifier` delegates final outcome selection to the new owner.
- Public static-web diagnostic surfaces remain unchanged.
- Existing status and summary wording is preserved.
- Direct selector tests cover summary precedence and fallback behavior.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --tests "dev.talos.runtime.verification.SourceDerivedArtifactVerifierTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

- Focused selector test: passed (`BUILD SUCCESSFUL`; 6 actionable tasks: 1
  executed, 5 up-to-date).
- Focused selector/facade/adjacent verifier tests: passed (`BUILD
  SUCCESSFUL`; 6 actionable tasks: 1 executed, 5 up-to-date).
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `git diff --check`: passed, line-ending warning only.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 8 executed, 6 up-to-date).
