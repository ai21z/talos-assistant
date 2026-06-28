# [T895-done-high] Expected-target projection must not require mutation of read sources or unnamed conventional files

Status: done
Priority: high

## Evidence Summary

- Source: post-mode-refactor 2-model installed-build sweep (2026-06-28), Opus-graded
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / b936cddd
- Verification status: scn-16 reproduced on BOTH qwen2.5-coder-14b and gpt-oss-20b; root cause code-verified; confirmed PRE-EXISTING (identical block in the pre-refactor baseline transcript), NOT a mode-refactor regression.

Observed (scn-16 read-then-copy): the prompt "Read helper.py and create a new file named helper_copy.py containing the exact same code as helper.py" produces a correct, clean `helper_copy.py` on disk, but the turn ends `BLOCKED_BY_POLICY` with "expected-target progress required mutation of remaining target(s): helper.py". The READ SOURCE `helper.py` was projected as a required mutation target. This is an OUTCOME-TRUTH violation: Talos reports a block while the requested work actually succeeded.

Related (same class, the owner's original dev-mode frustration): a "redesign the page" / "build a modern interactive landing page" request can be made to require creating `style.css` and `script.js` that the user never named and that do not exist, via the hardcoded conventional static-web triplet.

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH` (false block). Secondary: `ACTION_OBLIGATION`, `INTENT_BOUNDARY`.
Blocker level: release blocker (a trust-surface / outcome-truth defect). Why: Talos must never report a deterministic policy block for work it actually performed, and must not demand mutation of files the user only asked to read or never named.

## Architectural Hypothesis (root cause, code-verified)

Two distinct manifestations of one root: the expected-target set conflates "files to read" and "files to write", and the conventional static-web projection invents required files.

PART A — read source projected MUST_MUTATE:
- `MutationIntent.classificationReason` returns `explicit-read-then-mutation-request` for "read X and create/write Y" ([MutationIntent.java:293](src/main/java/dev/talos/runtime/MutationIntent.java:293), pattern `READ_THEN_MUTATION_REQUEST` at :235). On this path the resolver never runs the source/output split, so `expectedTargets = extractExpectedTargets(original) = {helper.py, helper_copy.py}` and `sourceEvidenceTargets` stays empty ([TaskContractResolver.java:440](src/main/java/dev/talos/runtime/task/TaskContractResolver.java:440)).
- Every expected target on a mutating contract is uniformly projected `MUST_MUTATE` ([TurnPolicyTrace.java:214](src/main/java/dev/talos/runtime/TurnPolicyTrace.java:214)); the block consumes the role-blind `contract.expectedTargets()` and requires EVERY entry to be mutated ([ExpectedTargetProgressAccounting.java:18-55](src/main/java/dev/talos/runtime/toolcall/ExpectedTargetProgressAccounting.java:18)). `helper.py` is read (non-mutating) so it is never satisfied -> false block emitted by `PendingActionObligationBreachGuard` / `PendingActionObligation` via `LoopState.failPendingActionObligationAfterInvalidToolCalls`.
- The existing lexical source-evidence stripping at [TaskContractResolver.java:464-473](src/main/java/dev/talos/runtime/task/TaskContractResolver.java:464) only captures "based on / from / using" spans (`SOURCE_EVIDENCE_SPAN`), so "Read X and create Y" is not captured.

PART B — unnamed conventional satellites required:
- `inferConventionalStaticWebTargets` ([TaskContractResolver.java:684-716](src/main/java/dev/talos/runtime/task/TaskContractResolver.java:684)) returns the hardcoded triplet `conventionalStaticWebTargets()` = [index.html, style.css, script.js] (:718) whenever a create-like web request is `deicticSite` ("the page"/"the site") OR `webSurface && namesStyleAndScript && strongSingularConvention`. `mentionsStyleAsset`/`mentionsScriptAsset` (:760, :777) match SOFT words ("modern", "visual", "interactive", "functional"), so a single-file page or a bare "redesign the page" demands creating css/js that were never named. (Note: it only fires when `expectedTargets` is empty, i.e. when no filenames were named, so scn-12 which names index.html/style.css/script.js explicitly is unaffected.)

Why a one-off patch is insufficient: both are the same invariant violation (read-only / not-requested files must not be hard mutation obligations). Fixing the projection at the source-of-truth fixes the false block without touching the downstream block/accounting.

## Goal

A target the user asked only to READ (read-then-copy source) is projected as source evidence, never a mutation obligation. Conventional static-web satellites that are neither named nor present are not required mutations. The genuine action-obligation block (anti-overclaim: a real write target left unmutated still blocks) is preserved unchanged.

## Non-Goals

- No change to the deterministic block / `ExpectedTargetProgressAccounting` / `PendingActionObligationBreachGuard` (fix the contract source-of-truth instead).
- Do NOT weaken the genuine mutation obligation: same-file "read X then mutate X" keeps X as MUST_MUTATE; a real unmutated write target still blocks.
- Do NOT break "build a website in an empty folder" (scn-12) or any explicit multi-file web create.
- No giving the resolver disk access (it is intentionally lexical, shared by ~40 read-only call sites).

## Implementation Notes (fix plan)

PART A (in `TaskContractResolver.resolveLegacyFromUserRequest`, the `mutationAllowed` block ~464-479):
- When `classificationReason == "explicit-read-then-mutation-request"`, extract the read source(s) (the file(s) in the "read|open ... and|then" clause) via a new `extractReadThenMutationSourceTargets` (reuse `TARGET_FILE` + `normalizeTarget`).
- Compute `createTargets = expectedTargets \ readSources`. ONLY if `createTargets` is non-empty (read source distinct from the write target): set `expectedTargets = createTargets` and merge `readSources` into `sourceEvidenceTargets`. If `createTargets` is empty (read source == only target), leave it MUST_MUTATE.

PART B (static-web triplet) is DEFERRED to T897 after deeper analysis. The conventional triplet is INTENDED + TESTED behavior for create-from-scratch website requests: `longFormWebsiteBriefEndingInCreateQuestionBecomesFileCreateContract` pins the full `{index.html, style.css, script.js}` for a loose "create that web page" with NO css/js named, and `exactRetrocatsAuditPromptIsStaticWebCreationWithScopedTailwindForbiddenTarget` does the same. A lexical "drop unnamed satellites" change would break that intended behavior. The real harm (demanding nonexistent css/js when REDESIGNING an existing single-file page) is disk-dependent: the resolver is intentionally lexical and cannot see that index.html already exists as a single file. That harm is partly mitigated by T896 (edit-existing -> FILE_EDIT avoids the create-only triplet inference) and otherwise needs a disk-aware obligation-layer fix, tracked in T897. T895 is therefore scoped to the read-source projection (Part A), the clean deterministic trust violation found in the sweep.

## Acceptance Criteria

- "Read helper.py and create helper_copy.py ..." -> contract FILE_CREATE, `expectedTargets == {helper_copy.py}`, `sourceEvidenceTargets == {helper.py}`, reason `explicit-read-then-mutation-request`.
- Writing only helper_copy.py satisfies the contract: NO `BLOCKED_BY_POLICY`, outcome COMPLETED.
- Same-file "Read script.js then replace ... in script.js" keeps `script.js` in expectedTargets (MUST_MUTATE) -> existing test stays green.
- A genuine unmutated write target still blocks (anti-overclaim preserved).
- Static-web triplet behavior is UNCHANGED by T895 (deferred to T897): all existing static-web create tests stay green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Red-first unit tests in `TaskContractResolverTest` (read-then-copy source split; deictic/loose static-web no longer requires satellites) and a `RolefulIntentRecoveryRegressionTest` assertion (helper.py = SOURCE_EVIDENCE, helper_copy.py = MUST_MUTATE, no remaining mutation target after helper_copy.py written). Keep green: `readThenReplaceInNamedFileBecomesMutationAllowedContract`, `naturalStyledInteractiveWebCreateInfersConventionalStaticTargets`, `PendingActionObligationBreachGuardTest.expectedTargetWrongMutationReturnsBreachDetail`, `RolefulIntentOutcomeRegressionTest.blockedAfterSuccessfulMutationReportsChangedTargetAndStaysBlocked`.

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.*" --tests "dev.talos.cli.modes.RolefulIntent*" --no-daemon
./gradlew.bat check --no-daemon
```

Installed manual test: re-run scn-16 on both pinned models; expect read source read-only + single write target + non-blocked COMPLETED.

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One-line `## [Unreleased]` CHANGELOG entry on landing. Ticket moved to done/ on close.

## Closeout (2026-06-28)

Implemented Part A (read-source projection) in `TaskContractResolver.resolveLegacyFromUserRequest`. Added pattern `READ_CLAUSE_BEFORE_MUTATION` + helper `extractReadThenMutationSourceTargets` to capture the file(s) in the leading "read|open ... and|then" clause. In the `mutationAllowed` block, for `classificationReason == "explicit-read-then-mutation-request"` and NOT the `readEvidenceTargetsAreAlsoMutationTargets` (read-the-current-then-rewrite) shape, the read source is moved to `sourceEvidenceTargets` and removed from `expectedTargets` ONLY when a genuine NON-forbidden write target remains distinct from it (explicit set-difference; forbidden-aware). So "read X and create Y" -> expected={Y}, sourceEvidence={X}; "read X then mutate X" and "... do not edit Z" keep their targets; a genuinely-unmutated write target still blocks (anti-overclaim preserved).

Two implementation corrections caught by the focused suite (TDD): (1) used an explicit set-difference instead of `withoutForbiddenTargets` (which is not a plain difference and polluted sourceEvidence on same-file prompts, breaking the "read script.js then fix" repair tests); (2) made the guard forbidden-aware (at this seam `expectedTargets` still contains the forbidden target since the forbidden-strip runs later, which had inverted the difference and kept the forbidden file).

Part B (static-web triplet) split to T897 after finding the triplet is INTENDED + TESTED for create-from-scratch (`longFormWebsiteBriefEndingInCreateQuestionBecomesFileCreateContract` pins the full triplet for a loose "create that web page" with no css/js named); a lexical drop would break it. The edit-existing harm is partly handled by T896 and otherwise needs a disk-aware obligation-layer fix (T897).

Tests: new `readThenCreateCopyProjectsReadSourceAsEvidenceNotMutationTarget` in `TaskContractResolverTest` (expected={helper_copy.py}, sourceEvidence={helper.py}). Focused net green: `dev.talos.runtime.task.*` + `dev.talos.runtime.toolcall.*` + `dev.talos.runtime.intent.*` + `dev.talos.cli.modes.RolefulIntent*` = 578 tests, 0 failures. Broad `check` at end of batch. Installed re-run of scn-16 is the manual confirmation.
