# [T896-done-low] Editing an existing file is mislabeled FILE_CREATE when the body says "add a ..."

Status: done
Priority: low

## Evidence Summary

- Source: post-mode-refactor 2-model sweep (2026-06-28), scn-17-edit-static-web grading
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / b936cddd
- Verification status: reproduced + root-cause code-verified. Behavior was correct (the edit applied, trust HELD); the CONTRACT LABEL is wrong.

Observed: "Edit index.html to add a Contact section ... Keep all existing content." (index.html already exists) is classified `FILE_CREATE` instead of `FILE_EDIT`. The edit still applied correctly and the trust surface held, so this is a label-accuracy defect, not a behavior break. But the wrong type can mislead downstream contract handling and traces.

## Classification

Primary taxonomy bucket: `CURRENT_TURN_FRAME` (task-type label accuracy). Blocker level: candidate follow-up (cosmetic, non-blocking). Why: behavior is correct; only the FILE_CREATE-vs-FILE_EDIT label is wrong.

## Architectural Hypothesis (root cause, code-verified)

The decision point is `TaskContractResolver.classify` ([TaskContractResolver.java:1002-1014](src/main/java/dev/talos/runtime/task/TaskContractResolver.java:1002)). For this input:
1. `MutationIntent.classificationReason` returns `explicit-mutation-verb-with-file-target` (the edit verb "edit" + named file "index.html").
2. `classify` falls through to the final line: `return containsAny(lower, CREATE_MARKERS) ? FILE_CREATE : FILE_EDIT;` ([:1013](src/main/java/dev/talos/runtime/task/TaskContractResolver.java:1013)).
3. `CREATE_MARKERS` ([:124-128](src/main/java/dev/talos/runtime/task/TaskContractResolver.java:124)) includes `"add a"` / `"add the"`. The phrase "...to add a Contact section" matches -> FILE_CREATE.

So the content-addition phrase "add a" (describing what to add INSIDE the file) is weighed equally with a file-creation verb, even though the request was recognized via an explicit edit-verb-on-named-file reason. The resolver is lexical-only (no disk access at this seam, confirmed across all ~40 call sites), so the fix must be lexical, not existence-based.

There is also a secondary carryover path (`ActiveTaskContextPolicy.contextualizedContract` re-derives FILE_CREATE from a parked PENDING_MUTATION operation=CREATE), but the single-turn root cause is line 1013. The owner's scn-17 is single-turn; this ticket fixes the single-turn classifier. The carryover path is out of scope (separate lane).

## Goal

Editing an existing file (an explicit edit verb on a named file) classifies `FILE_EDIT` even when the instruction body uses "add a/the <section>". Genuine creation ("Create ...", "Build ...", "Make ...", create-missing-files) stays `FILE_CREATE`.

## Non-Goals

- No disk-existence check in the resolver (it is intentionally lexical/shared).
- No change to the `ActiveTaskContextPolicy` create-context carryover (separate lane).
- No change to genuine create classification.

## Implementation Notes (fix plan)

In `TaskContractResolver.classify`, before the `CREATE_MARKERS` fallback, short-circuit to `FILE_EDIT` when `classificationReason == "explicit-mutation-verb-with-file-target"` AND the leading mutation verb is an edit/modify/change/update/fix/rewrite/replace/append/remove verb (not create/write/build/generate/scaffold/make). Add a small `startsWithEditNotCreateVerb(lower)` helper that inspects the leading verb after the existing prefix niceties. Keep the change scoped to that reason so "Create ..."/"Build ..." are untouched.

## Acceptance Criteria

- "Edit index.html to add a Contact section. Keep all existing content." -> `FILE_EDIT`.
- "Edit README.md to add the install steps." -> `FILE_EDIT`.
- "Create a README.md ..." / "Build a website ..." -> still `FILE_CREATE`.
- Existing edit/create classification tests stay green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Red-first unit tests in `TaskContractResolverTest` for the two FILE_EDIT cases above (fail today). Keep green: `explicitEditRequestBecomesFileEditContract`, `createRequestBecomesFileCreateContract`, `naturalStyledInteractiveWebCreateInfersConventionalStaticTargets`, `appendLineRequestBecomesFileEditContract`, `retryPreambleBeforeExplicitFileEditBecomesMutationAllowedContract`.

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One-line `## [Unreleased]` CHANGELOG entry on landing. Ticket moved to done/ on close.

## Closeout (2026-06-28)

Implemented in `TaskContractResolver.classify`. Added `EDIT_LEADING_VERB` / `CREATE_LEADING_VERB` patterns and `startsWithEditNotCreateVerb(lower)` (true when an edit verb occurs before any create verb). In the mutating fallback, when a CREATE_MARKER is present the type is now `startsWithEditNotCreateVerb ? FILE_EDIT : FILE_CREATE`, so "Edit index.html to add a Contact section" and "Edit README.md to add the install steps" classify FILE_EDIT, while "Create ..."/"Build ..." stay FILE_CREATE.

Implementation note (TDD): the first attempt gated on `classificationReason == "explicit-mutation-verb-with-file-target"`, but that reason did not fire for the test prompt, so the gate was dropped in favor of a reason-independent verb-ordering decision in the CREATE_MARKER branch. The secondary `ActiveTaskContextPolicy` create-context carryover path is out of scope (T897 lane).

Tests: new `editExistingFileToAddSectionStaysFileEditContract` in `TaskContractResolverTest`. Focused net `dev.talos.runtime.task.*` + `dev.talos.runtime.toolcall.*` + `dev.talos.cli.modes.*` = 1447 tests, 0 failures (no classification regressions). Broad `check` at end of batch; installed re-run of scn-17 confirms the FILE_EDIT label live.
