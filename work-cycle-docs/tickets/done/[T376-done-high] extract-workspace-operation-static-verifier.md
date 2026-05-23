# [T376-done-high] Extract Workspace Operation Static Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T376`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `acacc65a3c82284e28c50dc6a52d67a73f755edb`
Predecessor: `T375`

## Scope

T376 implements the first verification and outcome truthfulness hygiene slice
selected by T375.

The scope is deliberately narrow:

- extract workspace-operation postcondition verification out of
  `StaticTaskVerifier`;
- keep `StaticTaskVerifier.verify(...)` as the public orchestration entrypoint;
- keep user-facing verifier summaries, facts, problems, and final outcome
  wording unchanged;
- do not touch `ExecutionOutcome`, `OutcomeDominancePolicy`, `RepairPolicy`, or
  `ToolCallRepromptStage`.

## Implementation

Created:

- `src/main/java/dev/talos/runtime/verification/WorkspaceOperationStaticVerifier.java`

Changed:

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/test/java/dev/talos/runtime/verification/WorkspaceOperationStaticVerifierTest.java`

`WorkspaceOperationStaticVerifier` now owns:

- accumulation of `WorkspaceOperationPlan.PathEffect` values;
- copied/moved/renamed/deleted/created/batch path postcondition checks;
- workspace-operation facts and problems;
- mutation targets derived from operation destinations;
- expected target exemptions for source/deleted paths;
- basename aliases for moved/copied/renamed destination targets.

`StaticTaskVerifier` now delegates only workspace-operation plan verification to
the extracted component, then keeps existing orchestration:

- collect normal mutating path hints;
- add workspace-operation facts/problems;
- add workspace-operation mutation targets and expected-target exemptions;
- run expected target checks, task expectations, exact edit checks,
  source-derived artifact checks, and static web checks as before.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --no-daemon
```

Result: failed at `:compileTestJava` because
`WorkspaceOperationStaticVerifier` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --no-daemon
```

Result: passed after adding `WorkspaceOperationStaticVerifier` and delegating
from `StaticTaskVerifier`.

## Behavior Preservation

T376 is a structural extraction, not a behavior change.

The direct component test proves the extracted verifier exposes the same
workspace-operation facts, problems, mutation targets, expected target
exemptions, and aliases needed by `StaticTaskVerifier`.

The existing integration tests in `WorkspaceOperationStaticVerifierTest` still
exercise `StaticTaskVerifier.verify(...)` through tool-loop outcomes, so the
orchestrator delegation remains covered.

## Out Of Scope

T376 does not:

- rewrite `ExecutionOutcome`;
- change outcome dominance precedence;
- alter final-answer text;
- replace static repair prompt parsing;
- extract static web verification;
- extract source-derived artifact verification;
- add or relax architecture-boundary rules.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Result:

- RED `WorkspaceOperationStaticVerifierTest`: failed at `:compileTestJava`
  because `WorkspaceOperationStaticVerifier` did not exist.
- GREEN `WorkspaceOperationStaticVerifierTest`: passed.
- Focused `WorkspaceOperationStaticVerifierTest` plus
  `StaticTaskVerifierTest`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `git diff --check`: passed; output was limited to expected Windows
  line-ending warnings.
- `.\gradlew.bat check --no-daemon`: passed before recording verification
  (`BUILD SUCCESSFUL`, 14 actionable tasks: 6 executed, 8 up-to-date).
- Final post-ticket-update `.\gradlew.bat check --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 14 actionable tasks: 2 executed, 12 up-to-date).
