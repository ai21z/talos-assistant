# [T388-done-high] Extract Source-Derived Artifact Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T388`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `1b6f4c56`
Predecessor: `T387`

## Scope

T388 implements the post-T387 boundary inspection result:

```text
[T388] Extract source-derived artifact verifier
```

This is a behavior-preserving ownership extraction. It moves source-derived
artifact verification out of `StaticTaskVerifier` and into:

```text
src/main/java/dev/talos/runtime/verification/SourceDerivedArtifactVerifier.java
```

The existing `StaticTaskVerifier.verify(...)` public facade remains the
orchestrator. It delegates source-derived artifact checks and keeps final
`TaskVerificationResult` status and summary precedence unchanged.

## Source Inspection

Post-T387 inspection showed that `StaticTaskVerifier` still owned one coherent
source-derived verification block:

- summarizing-task applicability;
- generated target readback;
- source evidence readback;
- extractable PDF/DOCX/XLSX source evidence through `DocumentExtractionService`;
- file capability classification through `FileCapabilityPolicy`;
- instruction-echo detection;
- per-source distinctive term coverage;
- unsupported distinctive term detection for hallucinated prose;
- requested bullet-limit enforcement for source-derived summaries.

This block was separable from:

- expected/forbidden mutation target verification;
- exact edit replacement evidence;
- task expectation verification;
- workspace operation verification;
- static-web verification;
- final outcome summary selection.

## Implementation

`SourceDerivedArtifactVerifier` now owns:

- `verify(TaskContract, Path)`;
- the `Result` record carrying `required`, source-derived facts, and problems;
- source evidence extraction/readback;
- source-derived distinctive term matching;
- unsupported-term hallucination detection;
- instruction-echo detection;
- source-derived bullet-limit counting.

`StaticTaskVerifier` now:

- delegates to `SourceDerivedArtifactVerifier`;
- appends returned facts and problems;
- keeps the `sourceDerivedRequired` summary branch;
- keeps all public facade methods and non-source-derived verifier responsibilities.

## Behavior Preservation

T388 intentionally does not change runtime behavior or outcome wording:

- source-derived pass summary stays `Source-derived artifact verification passed.`;
- source-derived failure summary stays `Source-derived artifact verification failed.`;
- existing source-derived fact/problem wording is preserved;
- document extraction behavior is unchanged;
- unsupported-term hallucination detection is unchanged;
- exact edit, target scope, static-web, expectation, and workspace-operation
  verification behavior is untouched.

## Measurements

Measured after extraction:

| File | Lines | Role |
|---|---:|---|
| `StaticTaskVerifier.java` | 964 | Public verifier facade/orchestrator plus remaining verifier domains. |
| `SourceDerivedArtifactVerifier.java` | 265 | Source-derived artifact grounding, extraction, and hallucination verifier. |

Before T388, `StaticTaskVerifier.java` was 1270 lines on `1b6f4c56`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.SourceDerivedArtifactVerifierTest" --no-daemon
```

Result: failed at `:compileTestJava` because `SourceDerivedArtifactVerifier`
did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.SourceDerivedArtifactVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

Result: passed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.SourceDerivedArtifactVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
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

After T388 lands, do not assume the next extraction is mechanical.

The next inspection should decide whether the remaining coherent owner is:

- exact edit replacement verification;
- expected/forbidden mutation target verification;
- mutation target readback;
- final outcome summary selection.

Do not mix those responsibilities in one ticket unless source inspection proves
they are one ownership unit.
