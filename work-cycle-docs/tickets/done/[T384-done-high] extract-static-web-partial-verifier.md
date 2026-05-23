# [T384-done-high] Extract Static Web Partial Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T384`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `029bc8b1`
Predecessor: `T383`

## Scope

T384 extracts partial static-web verification from `StaticTaskVerifier` into a
package-private verifier:

```text
src/main/java/dev/talos/runtime/verification/StaticWebPartialVerifier.java
```

This is a behavior-preserving ownership extraction. It does not change runtime
behavior, diagnostic wording, final-answer wording, repair behavior, public
facade methods, task classification, static-web surface selection, or the
lower-level structure/form primitives extracted in T383.

## Source Decision

After T383, the remaining partial styled/functional methods no longer owned
HTML structure parsing or calculator/form primitive checks. Their remaining
responsibility is coherent:

- verify a partial styled web surface when only HTML/style evidence is present;
- verify a partial functional web surface when only HTML/script evidence is
  present;
- report missing linked or inline CSS/JavaScript for partial web tasks;
- report duplicate HTML IDs for partial functional checks;
- delegate HTML structure and form primitives to `StaticWebStructureVerifier`;
- delegate selector/link discovery to `StaticWebSelectorAnalyzer`.

That makes `StaticWebPartialVerifier` the correct next owner. Moving public
diagnostic facades or full selector diagnostics would still be premature.

## Implementation

`StaticWebPartialVerifier` now owns:

- partial styled-web verification;
- partial functional-web verification;
- primary HTML selection failure messages for partial checks;
- partial read-failure messages;
- missing stylesheet/inline-style checks;
- missing JavaScript/inline-script checks;
- linked asset existence checks for partial CSS/JS surfaces;
- duplicate HTML ID checks in partial functional verification;
- calculator/form static structure invocation for partial functional tasks.

`StaticTaskVerifier` still owns:

- public verifier facade methods;
- task verification result selection;
- `verifySmallWebWorkspace(...)` orchestration;
- full HTML/CSS/JavaScript selector coherence;
- read-only web diagnostic rendering;
- static selector search rendering;
- script import inspection rendering;
- static-web capability-profile routing decisions.

## Behavior Preservation

T384 preserves all existing user-facing fact/problem strings by moving the
same method bodies into the extracted package-private class and delegating from
the existing `StaticTaskVerifier` call sites.

No external consumers were rewired away from `StaticTaskVerifier`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebPartialVerifierTest" --no-daemon
```

Result: failed at `compileTestJava` because `StaticWebPartialVerifier` did not
exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebPartialVerifierTest" --no-daemon
```

Result: passed.

Focused preservation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebPartialVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.StaticWebStructureVerifierTest" --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --no-daemon
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

T384 intentionally does not:

- move `verifySmallWebWorkspace(...)`;
- move `currentWebDiagnostics(...)`;
- move `renderWebDiagnostics(...)`;
- move `renderScriptImportInspection(...)`;
- move `StaticWebImportIntent`;
- alter `StaticWebStructureVerifier`;
- alter `StaticWebSelectorAnalyzer`;
- alter `StaticWebSurfaceDetector`;
- rewire `AssistantTurnExecutor`, `ExecutionOutcome`, `RepairPolicy`,
  `ConditionalReviewFixPolicy`, or `ToolCallRepromptStage`.

## Next Step

After T384 lands, inspect whether the remaining static-web responsibility in
`StaticTaskVerifier` is now mostly public facade and orchestration, or whether
there is one more coherent lower-level primitive before stopping this lane.
