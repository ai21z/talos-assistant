# [T609-done-high] Deterministic roleful intent e2e regression pack

## Status

Done.

## Scope

Added deterministic scripted e2e coverage for the three live-audit roleful intent failures without committing raw live transcripts or depending on a live model.

This ticket is the renumbered form of the roleful intent lane's planned T585.

## Problem

The roleful intent lane fixed resolver, projection, reconciliation, continuation, and evidence paths in unit-level slices. The remaining risk was that those slices could pass independently while the end-to-end execution loop still:

- treated scoped output constraints as read-only or as broad static-web creation obligations,
- treated verification-purpose filenames as required mutation targets,
- reintroduced singular conventional filenames after workspace reconciliation,
- rendered false success or false blockage after scripted tool outcomes.

## Change

Added deterministic JSON scenarios:

- `84-roleful-scoped-extra-files-mutates-requested-target.json`
- `85-roleful-constraint-target-is-verify-only.json`
- `86-roleful-existing-static-web-targets-keep-plural-names.json`

Added a reusable fixture:

- `src/e2eTest/resources/fixtures/roleful-static-site/`

Added scenario assertions for:

- final file state,
- absence of stray files such as `improvements.txt`, `site/index.html`, `script.js`, and `style.css`,
- legacy trace `expectedTargets` / `forbiddenTargets`,
- roleful trace target entries,
- trace outcome classification,
- absence of false success.

## Runtime fixes exposed by the e2e pack

The pack exposed three integration holes that unit tickets had not fully closed:

1. `StaticWebCapabilityProfile` treated negated `create` phrases such as `Do not create extra files` as positive static-web creation intent. That caused CSS-only improvements to require separate HTML/CSS/JS asset mutations.
2. `StaticWebContinuationPlanner` rebuilt raw task contracts without workspace reconciliation, so continuation and verification paths could still name `script.js` / `style.css` after current-turn planning had reconciled to `scripts.js` / `styles.css`.
3. `TurnPolicyTrace` recomputed roleful targets directly from raw intent, so trace evidence could still show stale conventional `script.js` / `style.css` even when the active contract used reconciled plural targets.

Those fixes are intentionally narrow and directly tied to the deterministic scenarios.

## Tests

Added or updated:

- `JsonScenarioPackTest.rolefulScopedExtraFilesMutatesRequestedTarget`
- `JsonScenarioPackTest.rolefulConstraintTargetIsVerifyOnly`
- `JsonScenarioPackTest.rolefulExistingStaticWebTargetsKeepPluralNames`
- `StaticWebCapabilityProfileTest.scopedDoNotCreateExtraFilesDoesNotRequireSeparateAssetMutations`
- `ExpectedTargetProgressAccountingTest.workspaceReconciledPluralStaticWebTargetsSatisfyExpectedProgress`

## Verification

RED observed before production changes:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.rolefulScopedExtraFilesMutatesRequestedTarget" --tests "dev.talos.harness.JsonScenarioPackTest.rolefulConstraintTargetIsVerifyOnly" --tests "dev.talos.harness.JsonScenarioPackTest.rolefulExistingStaticWebTargetsKeepPluralNames" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.capability.StaticWebCapabilityProfileTest" --no-daemon
```

GREEN after implementation:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.rolefulScopedExtraFilesMutatesRequestedTarget" --tests "dev.talos.harness.JsonScenarioPackTest.rolefulConstraintTargetIsVerifyOnly" --tests "dev.talos.harness.JsonScenarioPackTest.rolefulExistingStaticWebTargetsKeepPluralNames" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.capability.StaticWebCapabilityProfileTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --tests "dev.talos.runtime.trace.LocalTurnTracePolicyTraceTest" --tests "dev.talos.runtime.toolcall.StaticWebContinuationPlannerTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.task.*" --tests "dev.talos.runtime.toolcall.*" --no-daemon
```

## Non-goals

- Did not add live model audit evidence.
- Did not add raw live transcripts.
- Did not introduce an LLM intent advisor.
- Did not rewrite `TaskContractResolver`.
- Did not resume broad architecture or `AssistantTurnExecutor` refactoring.
