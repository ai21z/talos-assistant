# [T378-done-high] Extract Static Web Selector Analyzer

Status: done
Priority: high
Date: 2026-05-23
Branch: `T378`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `380c79996e26eb7817ca3a84880a5676293d91e3`
Predecessor: `T377`

## Scope

T378 implements the first static-web verification ownership slice selected by
T377.

The scope is deliberately narrow:

- extract selector, linkage, content, and button-result static-web facts into a
  package-local analyzer;
- keep `StaticTaskVerifier` as the public facade for post-apply verification,
  read-only diagnostics, repair selector facts, and CLI answer overrides;
- preserve current verifier statuses, facts, problems, and diagnostic strings;
- do not move static-web import intent, partial styled/functional verification,
  repair routing, final-answer shaping, or outcome dominance.

## Implementation

Created:

- `src/main/java/dev/talos/runtime/verification/StaticWebSelectorAnalyzer.java`
- `src/test/java/dev/talos/runtime/verification/StaticWebSelectorAnalyzerTest.java`

Changed:

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`

`StaticWebSelectorAnalyzer` now owns:

- HTML class and ID extraction;
- linked CSS and JavaScript discovery;
- preferred linked/target-aware CSS and JavaScript selection;
- CSS class, ID, and bare-element selector extraction;
- JavaScript class, dynamic class, and ID extraction;
- placeholder/content checks for HTML, CSS, and JavaScript;
- duplicate/missing linked asset checks;
- selector mismatch checks;
- requested `#run-button` / `#result` behavior checks;
- generic button-result diagnostic checks;
- current selector inspection rendering.

`StaticTaskVerifier` still owns:

- the public `verify(...)` entrypoint;
- static-web post-apply orchestration;
- primary/target-aware web surface selection;
- read-only diagnostics facade methods;
- static web import inspection facade;
- partial styled/functional web checks;
- calculator/form static structure checks;
- HTML structure checks;
- task verification result selection.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --no-daemon
```

Result: failed at `:compileTestJava` because `StaticWebSelectorAnalyzer` did
not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --no-daemon
```

Result: passed after adding `StaticWebSelectorAnalyzer`.

Focused behavior preservation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.policy.ConditionalReviewFixPolicyTest" --no-daemon
```

Result: passed.

## Behavior Preservation

T378 is a structural extraction, not a behavior change.

The new direct analyzer test proves the extracted component owns selector,
linkage, and button-result diagnostic facts directly. Existing
`StaticTaskVerifierTest` coverage still exercises the public verifier and
read-only diagnostic facade. `AssistantTurnExecutorTest`, `RepairPolicyTest`,
and `ConditionalReviewFixPolicyTest` cover the major consumers of the
unchanged facade.

## Out Of Scope

T378 does not:

- move `StaticWebImportIntent`;
- rewrite `AssistantTurnExecutor`;
- rewrite `ConditionalReviewFixPolicy`;
- rewrite `RepairPolicy`;
- change `ExecutionOutcome`;
- change static-web capability profile classification;
- change repair-loop routing;
- change final-answer wording;
- extract all of `verifySmallWebWorkspace(...)`;
- add or relax architecture-boundary rules.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.policy.ConditionalReviewFixPolicyTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Result:

- RED `StaticWebSelectorAnalyzerTest`: failed at `:compileTestJava` because
  `StaticWebSelectorAnalyzer` did not exist.
- GREEN `StaticWebSelectorAnalyzerTest`: passed.
- Focused `StaticWebSelectorAnalyzerTest` plus `StaticTaskVerifierTest`:
  passed.
- Focused analyzer/verifier/consumer suite: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 1 actionable task: 1 executed).
- `git diff --check`: passed; output was limited to expected Windows
  line-ending warnings.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`, 14
  actionable tasks: 6 executed, 8 up-to-date).
- Final post-ticket-update `.\gradlew.bat check --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 14 actionable tasks: 2 executed, 12 up-to-date).
