# [T383-done-high] Extract Static Web Structure Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T383`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `3e2b0bb0`
Predecessor: `T382`

## Scope

T383 extracts static-web structure and form primitives from
`StaticTaskVerifier` into a package-private verifier:

```text
src/main/java/dev/talos/runtime/verification/StaticWebStructureVerifier.java
```

This is a behavior-preserving ownership extraction. It does not change runtime
behavior, diagnostic wording, final-answer wording, repair behavior, public
facade methods, task classification, or static-web surface selection.

## Implementation

`StaticWebStructureVerifier` now owns:

- empty HTML detection;
- malformed closing tag detection;
- unclosed structural tag detection;
- complete-tag counting;
- nonblank inline `<script>` detection;
- nonblank inline `<style>` detection;
- calculator/form structure checks;
- BMI-specific weight and height input checks;
- result output detection.

`StaticTaskVerifier` still owns:

- public verifier facade methods;
- task verification result selection;
- `verifySmallWebWorkspace(...)` orchestration;
- partial styled/functional verification orchestration;
- read-only web diagnostic rendering;
- static selector search rendering;
- script import inspection rendering;
- static-web capability-profile decisions.

## Behavior Preservation

T383 preserves the existing user-facing problem/fact strings by moving the
same logic and literals into the extracted package-private class, then
delegating from the existing call sites.

No consumers were rewired away from `StaticTaskVerifier`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebStructureVerifierTest" --no-daemon
```

Result: failed at `compileTestJava` because `StaticWebStructureVerifier` did
not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebStructureVerifierTest" --no-daemon
```

Result: passed.

Focused preservation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebStructureVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --no-daemon
```

Result: passed.

Adjacent runtime/repair preservation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.policy.ConditionalReviewFixPolicyTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
```

Result: passed.

## Closeout Verification

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed.

```powershell
git diff --check
```

Result: passed, with the existing line-ending warning for
`StaticTaskVerifier.java`.

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed.

## Out Of Scope

T383 intentionally does not:

- move `verifyPartialStyledWebWorkspace(...)`;
- move `verifyPartialFunctionalWebWorkspace(...)`;
- move `currentWebDiagnostics(...)`;
- move `renderWebDiagnostics(...)`;
- move `renderScriptImportInspection(...)`;
- move `StaticWebImportIntent`;
- alter `StaticWebSelectorAnalyzer`;
- alter `StaticWebSurfaceDetector`;
- rewire `AssistantTurnExecutor`, `ExecutionOutcome`, `RepairPolicy`,
  `ConditionalReviewFixPolicy`, or `ToolCallRepromptStage`.

## Next Step

After T383 lands, inspect whether partial styled/functional verification now
has a clean extraction boundary. Do not assume the next implementation ticket
should move partial verification without another source inspection pass.
