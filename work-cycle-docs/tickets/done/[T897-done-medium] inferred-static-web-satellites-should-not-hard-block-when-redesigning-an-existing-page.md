# [T897-done-medium] Inferred static-web satellites must not be a hard mutation obligation when redesigning an existing single-file page

Status: done
Priority: medium
Blocker level: future milestone (deferred-beyond-this-arc). Discovered while scoping T895; split out because the clean fix is disk-aware, not lexical.

## Resolution 2026-06-29

Implemented the disk-aware obligation-layer route described below, not a
role-tagging/`MAY_MUTATE` refactor. `TaskContractResolver` still projects the
conventional static-web triplet where the existing create tests require it.
`ExpectedTargetProgressAccounting` now removes absent, unnamed conventional
`style.css`/`styles.css`/`script.js`/`scripts.js` satellites only from the hard
remaining-mutation-target calculation when all of these are true:

- the contract is not a create-from-scratch `FILE_CREATE` task;
- the current workspace is still a single-file static page rooted at
  `index.html` with no root `.css`/`.js` files;
- `index.html` was successfully mutated in this turn;
- the latest request is an existing-page/site redesign/improvement shape;
- the satellite was not explicitly named and does not exist on disk.

Explicit filenames and create-from-scratch triplet prompts remain hard expected
targets. Deterministic E2E now covers a contextual existing single-file
redesign where the model reads `index.html`, writes only `index.html` with
inline CSS/JS, and does not end `BLOCKED_BY_POLICY` for missing `style.css` or
`script.js`.

Verification:

- `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest"` red before the fix on `[script.js, style.css]`, then green after.
- `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --tests "dev.talos.runtime.toolcall.ToolRepromptObligationSelectorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.task.WorkspaceTargetReconcilerTest" --tests "dev.talos.runtime.toolcall.StaticWebRewriteGroundingGuardTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.toolcall.RolefulIntentRecoveryRegressionTest"` passed.
- `.\gradlew.bat e2eTest --tests "dev.talos.harness.ExecutorScenarioTest.t897_existing_single_file_static_web_redesign_does_not_hard_block_absent_inferred_satellites"` passed.
- `.\gradlew.bat e2eTest --tests "dev.talos.harness.ExecutorScenarioTest" --tests "dev.talos.harness.JsonScenarioPackTest.multiFileWebCreateContinuesUntilExpectedTargets" --tests "dev.talos.harness.JsonScenarioPackTest.staticVerificationContinuationPreservesScriptsJs" --tests "dev.talos.harness.JsonScenarioPackTest.rolefulExistingStaticWebTargetsKeepPluralNames" --tests "dev.talos.harness.JsonScenarioPackTest.staticWebInteractionFailureRepairsMutatedTargets"` passed.

> Update 2026-06-28: the READ-evidence sibling of this bug (plan/ask read-only demanding reads of nonexistent inferred satellites) was fixed and closed as T900 (see work-cycle-docs/tickets/done/[T900-done-medium] ...). T897 now covers ONLY the remaining MUTATION facet below. The read fix could be cleanly disk-aware (you can never read a nonexistent file); the mutation facet cannot use a naive disk-existence drop because create-from-scratch satellites are also absent on disk, so dropping them would regress the intended "keep creating until the triplet is done" behavior (T98/T99/T100). The mutation fix therefore still needs the inferred-vs-named + create-vs-redesign distinction at the mutation-obligation layer.

## Evidence Summary

- Source: T895 investigation (2026-06-28) + the owner's original dev-mode redesign trace
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / b936cddd
- Verification status: root cause code-verified; intended-behavior conflict confirmed against existing tests.

Observed (the owner's original dev-mode bug): "make this better / complete redesign" of an existing single-file `index.html` (CSS inlined, no JS) produced a contract whose expected targets were `[index.html, style.css, script.js]`, demanding creation of `style.css`/`script.js` that the page does not have and the user never named. This helped derail the local model and is an over-projection.

## Why this is NOT the same fix as T895 (the key finding)

The conventional static-web triplet is INTENDED and TESTED for create-from-scratch website requests:
- `longFormWebsiteBriefEndingInCreateQuestionBecomesFileCreateContract` ([TaskContractResolverTest.java:516-529](src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java:516)) asserts the full `{index.html, style.css, script.js}` for "I want a cool modern looking webpage for a synthwave band ... Can you create that web page?" with NO css/js named.
- `exactRetrocatsAuditPromptIsStaticWebCreationWithScopedTailwindForbiddenTarget` ([:401](src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java:401)) and `naturalStyledInteractiveWebCreateInfersConventionalStaticTargets` ([:333](src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java:333)) similarly pin the triplet.

So a lexical "only project satellites that are explicitly named" change (the obvious one) would BREAK intended create-from-scratch behavior. The harm only appears when the SAME loose request targets an EXISTING single-file page (redesign/edit), which is a DISK-STATE distinction. `TaskContractResolver` is intentionally lexical (no disk access, shared by ~40 read-only call sites — confirmed at every call site), so it cannot tell "create a new site" from "redesign the existing single-file page". `inferConventionalStaticWebTargets` fires only when `expectedTargets` is empty (no filenames named), so both shapes collide there.

## Architectural Hypothesis (fix direction)

Make the INFERRED satellites (style.css/script.js that were neither named nor present on disk) a SOFT/optional target rather than a hard mutation obligation, enforced at the disk-aware obligation/runtime layer (which DOES know the workspace), not at the lexical resolver:
- Tag conventionally-inferred satellites with an optional role (e.g. `TargetRole.MAY_MUTATE`) instead of `MUST_MUTATE`, so creating them is encouraged but their absence does not produce `BLOCKED_BY_POLICY`; OR
- Have the action-obligation / `ExpectedTargetProgressAccounting` layer drop an inferred satellite from "remaining required targets" when it does not exist on disk and was not named (the runtime has the workspace and can make this call safely).
Either way: a true create-from-scratch still gets the triplet (and is encouraged to write all three), while a redesign of an existing single-file page is satisfied by editing index.html and is never blocked for not inventing css/js.

T896 (edit-of-existing-file -> FILE_EDIT) already reduces this for the named-file edit case, because a FILE_EDIT contract does not trigger the create-only triplet inference. T897 covers the remaining unnamed/deictic redesign case and the create-time obligation softening.

Likely code areas: `TaskContractResolver.inferConventionalStaticWebTargets` (tag inferred satellites), `TaskContractCompiler` / `TaskIntentResolver` (carry the optional role), `ExpectedTargetProgressAccounting` / the obligation guard (disk-aware drop of nonexistent inferred satellites).

## Non-Goals

- Do NOT remove the create-from-scratch triplet (keep the pinned create tests green).
- No lexical "drop unnamed satellites" hack (breaks intended behavior).
- No bypassing approval / checkpoint / outcome-truth.

## Acceptance Criteria

- Create-from-scratch website requests still project + are encouraged to create the full triplet (all existing static-web create tests green).
- A redesign/edit of an existing single-file page is NOT blocked for failing to create css/js it never had; the inferred satellites are optional, not a hard obligation.
- A genuine create that omits a satellite is handled by the existing optional-downgrade verifier path, not a hard block.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Deterministic e2e: a redesign of an existing single-file index.html does not end BLOCKED_BY_POLICY for missing style.css/script.js. Unit: inferred satellites carry the optional role; named/explicit satellites stay required. Keep green: the create-from-scratch triplet tests above.

## Work-Test Cycle Notes

- Inner dev loop when implemented; no version bump per edit. Deferred-beyond-this-arc until prioritized.
