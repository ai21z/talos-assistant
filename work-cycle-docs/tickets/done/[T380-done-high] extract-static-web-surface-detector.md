# [T380-done-high] Extract Static Web Surface Detector

Status: done
Priority: high
Date: 2026-05-23
Branch: `T380`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `c5750a3e087748f3c266368a15f2cd7b6ee9377a`
Predecessor: `T379`

## Scope

T380 implements the static-web surface detection extraction selected by T379.

The scope is deliberately narrow:

- create a package-local `StaticWebSurfaceDetector`;
- move static-web surface discovery, target-aware surface fallback, preferred
  target selection, primary read completeness, visible web-file filtering, and
  primary HTML fallback out of `StaticTaskVerifier`;
- keep `StaticTaskVerifier` as the public facade for existing CLI, repair, and
  outcome consumers;
- preserve current verifier statuses, facts, problems, diagnostics, repair
  wording, and final-answer behavior;
- do not move partial styled/functional verification.

## Implementation

Created:

- `src/main/java/dev/talos/runtime/verification/StaticWebSurfaceDetector.java`
- `src/test/java/dev/talos/runtime/verification/StaticWebSurfaceDetectorTest.java`

Changed:

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`

`StaticWebSurfaceDetector` now owns:

- obvious small static-web surface discovery;
- target-aware static-web surface discovery for mixed workspaces;
- visible root file enumeration and hidden-file filtering;
- static-web file extension filtering for root-level surfaces;
- preferred web target selection from expected and mutated paths;
- primary read-completeness checks by filename;
- primary HTML target fallback for script-import inspection;
- primary HTML/CSS/JavaScript surface presence checks.

`StaticTaskVerifier` still owns:

- the public `verify(...)` entrypoint;
- static-web post-apply orchestration;
- read-only diagnostics facade methods;
- static selector search rendering;
- static web import inspection rendering;
- partial styled web verification;
- partial functional web verification;
- HTML structure checks;
- calculator/form static structure checks;
- task verification result selection.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --no-daemon
```

Result: failed at `:compileTestJava` because `StaticWebSurfaceDetector` did
not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --no-daemon
```

Result: passed after adding `StaticWebSurfaceDetector` and delegating from
`StaticTaskVerifier`.

Focused behavior preservation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon
```

Result: passed.

## Behavior Preservation

T380 is a structural extraction, not a behavior change.

The new detector tests pin the extracted surface-discovery behavior directly.
Existing `StaticTaskVerifierTest` coverage still exercises post-apply static
web verification and read-only diagnostics through the stable facade.
`AssistantTurnExecutorTest` and `RepairPolicyTest` cover the primary consumer
paths that use the facade for deterministic final-answer overrides and repair
context enrichment.

## Out Of Scope

T380 does not:

- move `verifyPartialStyledWebWorkspace(...)`;
- move `verifyPartialFunctionalWebWorkspace(...)`;
- change `StaticWebCapabilityProfile`;
- change `TargetSurface`;
- change `StaticWebImportIntent`;
- change read-only diagnostic wording;
- change repair prompt wording;
- change final-answer wording;
- rewrite `AssistantTurnExecutor`;
- rewrite `RepairPolicy`;
- add or relax architecture-boundary rules.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Result:

- RED `StaticWebSurfaceDetectorTest`: failed at `:compileTestJava` because
  `StaticWebSurfaceDetector` did not exist.
- GREEN `StaticWebSurfaceDetectorTest`: passed.
- Focused detector/verifier/consumer suite: passed.
- `git diff --check`: passed; output was limited to expected Windows
  line-ending warnings.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 1 actionable task: 1 executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`, 14
  actionable tasks: 6 executed, 8 up-to-date).
- Final post-ticket-update `.\gradlew.bat check --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 14 actionable tasks: 2 executed, 12 up-to-date).
