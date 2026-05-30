# [T600] Roleful intent lane decision and test matrix

## Summary

T600 is a no-code decision ticket that opens the roleful intent fix lane.

Decision: the next implementation ticket should add only inert roleful intent
value types behind the existing task-contract surface.

```text
[T601] Add roleful intent value types
```

This lane fixes the current highest-risk execution defect: lexical intent and
flat target binding. The goal is not broad architecture cleanup. The goal is to
make Talos stop confusing scoped constraints, verification mentions, source
evidence, and conventional filenames with required mutation targets.

Do not implement extraction, resolver behavior changes, workspace
reconciliation, trace schema changes, live-audit automation, or LLM advisory
intent classification in T600. Phase 5 from the prior plan is intentionally
excluded from this lane. Mutation authority and safety gates remain
deterministic.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 232c4ba0
talosVersion = 0.9.9
```

Predecessor:

```text
T599 = Trace/artifact evidence lane closeout
```

The submitted plan used T576-T586 as provisional ticket numbers. Current beta
already contains T576-T599. Therefore this lane is renumbered to T600-T610.

## Source Inspected

Primary files inspected:

| File | Lines | Current responsibility |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/MutationIntent.java` | 477 | Lexical mutation intent, read-only negation, scoped limiter detection. |
| `src/main/java/dev/talos/runtime/task/TaskContractResolver.java` | 1354 | Task type, expected target extraction, source/forbidden target extraction, static-web target defaults. |
| `src/main/java/dev/talos/runtime/task/TaskContract.java` | 87 | Flat compatibility projection consumed by downstream runtime policy. |
| `src/main/java/dev/talos/runtime/toolcall/ExpectedTargetProgressAccounting.java` | 93 | Expected-target mutation progress accounting in the tool loop. |
| `src/main/java/dev/talos/runtime/toolcall/StaticWebContinuationPlanner.java` | 545 | Static-web continuation target naming and repair prompt construction. |
| `docs/architecture/01-execution-discipline-and-local-trust.md` | 351 | Architecture direction for task intent ownership. |
| `docs/architecture/02-runtime-policy-ownership-map.md` | 627 | Runtime policy ownership map and future `TaskIntentPolicy` boundary. |
| `work-cycle-docs/tickets/done/[T599-done-high] trace-artifact-evidence-lane-closeout.md` | 247 | Prior lane closeout and next-lane handoff. |

## Current Source Shape

`MutationIntent` still treats read-only negation as an early global veto before
positive mutation patterns:

```text
MutationIntent.java:237 -> containsGlobalReadOnlyNegation(lower)
MutationIntent.java:245-246 -> explicit request patterns checked afterward
```

`READ_ONLY_NEGATIONS` still contains broad mutation blockers including
`do not create`, while `isScopedLimiter(...)` handles "other files" style
constraints but not the observed "extra files" scoped-output constraint:

```text
MutationIntent.java:108-117 -> READ_ONLY_NEGATIONS
MutationIntent.java:448-470 -> isScopedLimiter(...)
```

`TaskContractResolver.extractExpectedTargets(...)` still runs filename patterns
over the whole prompt and returns a flat `Set<String>` without target roles:

```text
TaskContractResolver.java:436-455 -> extractExpectedTargets(...)
```

The static-web target fallback still has singular conventional names:

```text
TaskContractResolver.java:590-595 -> index.html, style.css, script.js
```

`TaskContract` still projects target state into flat sets:

```text
expectedTargets
sourceEvidenceTargets
forbiddenTargets
```

`ExpectedTargetProgressAccounting` still derives remaining required mutation
targets from `TaskContract.expectedTargets()` and reports any unsatisfied entry
as remaining mutation work:

```text
ExpectedTargetProgressAccounting.java:17-51
```

That is the mechanism behind the observed failures: the runtime has no typed
way to distinguish "must mutate this file" from "verify this other file still
works", "do not touch this file", "read this as source evidence", or "this file
was merely mentioned".

## Lane Decision

Add a deterministic roleful intent layer behind the existing task contract.

New internal package:

```text
dev.talos.runtime.intent
```

Initial internal types:

```text
TaskIntent
ArtifactTargetSet
TargetRef
TargetRole
TargetSource
IntentDerivation
TaskIntentResolver
TaskContractCompiler
```

Initial target roles:

| Role | Meaning |
| --- | --- |
| `MUST_MUTATE` | The current task requires mutation of this target. |
| `VERIFY_ONLY` | The current task requires evidence/verification involving this target, not mutation progress. |
| `SOURCE_EVIDENCE` | The target is read/input evidence for the requested work. |
| `FORBIDDEN` | The target must not be mutated. |
| `MENTIONED_ONLY` | The target is trace/debug evidence only; no obligation. |
| `OUTPUT_DESTINATION` | The target is an artifact destination and counts as expected output. |
| `MUST_READ` | The target must be inspected to answer or plan safely. |
| `MAY_MUTATE` | The target may be changed if needed but is not a required mutation target. |

Compatibility rule:

- Keep `TaskContractResolver.fromUserRequest(...)` stable.
- Keep `TaskContractResolver.fromMessages(...)` stable.
- Keep `TaskContract` as the compatibility projection.
- New downstream code may consume roleful intent directly only after projection
  parity is tested.
- No downstream behavior may depend on raw filename mentions without a role.

Projection rules for this lane:

- `TaskContract.expectedTargets = MUST_MUTATE + OUTPUT_DESTINATION`
- `TaskContract.sourceEvidenceTargets = SOURCE_EVIDENCE + source-bound MUST_READ`
- `TaskContract.forbiddenTargets = FORBIDDEN`
- `VERIFY_ONLY` targets trigger verification/evidence, not mutation progress.
- `MENTIONED_ONLY` targets are trace/debug evidence only, never mutation
  obligations.

## Acceptance Matrix

| ID | Prompt or workspace condition | Current risk | Required roleful result | Compatibility projection |
| --- | --- | --- | --- | --- |
| A | `Improve only styles.css. Do not create extra files. Do not modify index.html or scripts.js.` | Scoped output/file constraints can be misclassified as global read-only. | Mutating contract. `styles.css = MUST_MUTATE`; `index.html = FORBIDDEN`; `scripts.js = FORBIDDEN`; `extra files = FORBIDDEN`; no global read-only. | `expectedTargets=[styles.css]`; `forbiddenTargets=[index.html,scripts.js]`; mutation allowed. |
| B | `Rewrite styles.css so index.html still works.` | Constraint mention can become required mutation target. | `styles.css = MUST_MUTATE`; `index.html = VERIFY_ONLY`. | `expectedTargets=[styles.css]`; `index.html` excluded from mutation-progress accounting. |
| C | Workspace has `scripts.js` and no `script.js`; static-web repair mentions JavaScript generically. | Conventional singular target can be invented despite workspace evidence. | Existing `scripts.js` is the candidate target; no invented `script.js`. | Prompt/trace/continuation use `scripts.js`. |
| D | Workspace has `styles.css` and no `style.css`; static-web repair mentions CSS generically. | Conventional singular target can be invented despite workspace evidence. | Existing `styles.css` is the candidate target; no invented `style.css`. | Prompt/trace/continuation use `styles.css`. |
| E | Workspace has both `script.js` and `scripts.js`; user says "fix the JavaScript". | Runtime may silently guess from convention. | Ambiguous existing targets remain unresolved until evidence or user request disambiguates. | No silent conventional target substitution. |
| F | `Review index.html. Do not change anything.` | Regression risk from loosening negation logic. | Read-only/advisory. `index.html = MUST_READ` or `VERIFY_ONLY`, no mutation role. | mutation not allowed; mutating tools hidden. |
| G | `What would you change in styles.css? Do not edit files.` | Regression risk from positive file mention plus scoped intent work. | Read-only/advisory. `styles.css = MUST_READ` or `MENTIONED_ONLY`, no mutation role. | mutation not allowed; mutating tools hidden. |

## Renumbered Ticket Plan

| Ticket | Prior provisional | Scope |
| --- | --- | --- |
| T600 | T576 | Intent lane decision and test matrix. No runtime code. |
| T601 | T577 | Add roleful intent value types. Inert only. |
| T602 | T578 | Add `TaskIntent` and `TaskContractCompiler`. |
| T603 | T579 | Wire resolver in parity mode. |
| T604 | T580 | Fix scoped negation failure A. |
| T605 | T581 | Fix constraint mention failure B. |
| T606 | T582 | Add workspace target reconciliation. |
| T607 | T583 | Fix static-web continuation naming. |
| T608 | T584 | Add roleful trace and prompt-debug evidence. |
| T609 | T585 | Add deterministic E2E regression pack. |
| T610 | T586 | Lane closeout and next-move decision. |

## Ticket Acceptance Notes

### T601 - Add Roleful Intent Value Types

Add only inert value types and focused unit tests. No resolver wiring. No
behavior change.

Acceptance:

- `TargetRole` covers the initial role set.
- `ArtifactTargetSet` preserves role, normalized path, source span/text, and
  confidence/derivation.
- Duplicate target references preserve strongest role by deterministic
  precedence:
  `FORBIDDEN > MUST_MUTATE > OUTPUT_DESTINATION > MUST_READ > SOURCE_EVIDENCE > VERIFY_ONLY > MAY_MUTATE > MENTIONED_ONLY`.
- No production behavior changes.

Tests:

- `TargetRoleTest`
- `ArtifactTargetSetTest`

### T602 - Add TaskIntent And Compatibility Compiler

Add `TaskIntent` and `TaskContractCompiler`.

Acceptance:

- Manually constructed `TaskIntent` projects to the current `TaskContract`
  shape.
- `VERIFY_ONLY` does not enter `expectedTargets`.
- `FORBIDDEN` enters `forbiddenTargets`.
- `SOURCE_EVIDENCE` enters `sourceEvidenceTargets`.
- Existing `TaskContractResolver` behavior remains unchanged.

Tests:

- `TaskContractCompilerTest`
- Projection tests for all initial roles.

### T603 - Wire Resolver In Parity Mode

Introduce `TaskIntentResolver` behind `TaskContractResolver`, initially in
parity mode.

Acceptance:

- `TaskContractResolver.fromUserRequest(...)` delegates through
  `TaskIntentResolver -> TaskContractCompiler`.
- Existing classification and target tests pass unchanged.
- Prompt-debug and trace still show legacy `TaskContract` fields.
- No live-audit failure is fixed yet in this ticket.

Tests:

- Existing `TaskContractResolverTest`.
- New parity tests comparing old extracted fields against projected fields for
  representative existing prompts.

### T604 - Fix Scoped Negation Failure A

Behavior change ticket.

RED test first:

```text
Improve only styles.css. Do not create extra files. Do not modify index.html or scripts.js.
```

Current expected RED: classified `READ_ONLY_QA` or equivalent
`global-read-only-negation`.

Desired GREEN: mutating contract; mutation allowed;
`styles.css = MUST_MUTATE`; `index.html/scripts.js = FORBIDDEN`.

Implementation constraints:

- Do not patch by merely adding `"extra files"` to `isScopedLimiter(...)`.
- Segment clauses enough to classify `do not create extra files` as a scoped
  output constraint when paired with an explicit mutation directive.
- Preserve true global read-only prompts.

Tests:

- `TaskIntentResolverTest`
- `TaskContractResolverTest`
- Tool-surface test proving write/edit tools are visible for the mutating
  prompt.
- Negative test proving `Review files. Do not create files.` remains read-only.

### T605 - Fix Constraint Mention Failure B

Behavior change ticket.

RED test first:

```text
Rewrite styles.css so index.html still works.
```

Current expected RED: `expectedTargets=[index.html, styles.css]`.

Desired GREEN: `styles.css = MUST_MUTATE`; `index.html = VERIFY_ONLY`;
projected `expectedTargets=[styles.css]`.

Implementation constraints:

- Treat purpose/constraint clauses such as `so X still works`,
  `without breaking X`, and `compatible with X` as `VERIFY_ONLY`.
- Update expected-target progress accounting to consume only the projected
  `MUST_MUTATE + OUTPUT_DESTINATION` set.
- Ensure successful mutation is not rendered `BLOCKED` solely because a
  `VERIFY_ONLY` target was not mutated.
- Ensure verification can still run after successful mutation.

Tests:

- Resolver role test.
- Progress-accounting test.
- Outcome/rendering test for `mutationStatus=SUCCEEDED` with no remaining
  must-mutate target.
- Static verifier invocation path test where feasible.

### T606 - Add Workspace Target Reconciliation

Behavior change ticket focused on singular/plural drift.

RED tests first:

- Workspace contains `scripts.js`, not `script.js`; static-web task mentioning
  JavaScript should resolve to `scripts.js`.
- Workspace contains `styles.css`, not `style.css`; static-web task mentioning
  CSS should resolve to `styles.css`.
- Workspace contains both singular and plural variants; Talos must not silently
  guess a conventional target.

Implementation constraints:

- Add `WorkspaceTargetReconciler`.
- Do not inject workspace filesystem concerns into pure `TaskIntentResolver`.
- Apply reconciliation at the current-turn planning boundary where workspace
  context exists.
- Conventional names are allowed only when creating a new conventional static
  site and no conflicting existing file evidence exists.

Tests:

- Reconciler unit tests with fake workspace file sets.
- Current-turn planning/projection test proving reconciled targets reach the
  prompt/trace.
- Regression test for `scripts.js` exact-name preservation.

### T607 - Fix StaticWebContinuationPlanner Naming

Behavior change ticket separate from resolver reconciliation.

RED test first:

- Static verifier problem says missing JavaScript file `scripts.js`;
  continuation/remediation text currently names `script.js`.
- Desired GREEN: all continuation obligations and user-visible stop text name
  `scripts.js`.

Implementation constraints:

- Derive continuation targets from verifier problem payload/backtick target
  when present.
- Use conventional `script.js` only when the verifier did not name a file and
  no workspace evidence contradicts it.

Tests:

- `StaticWebContinuationPlannerTest`
- `ToolRepromptMessageOverlayTest`
- E2E scenario asserting the answer does not contain the wrong singular target.

### T608 - Add Roleful Trace And Prompt-Debug Evidence

Evidence ticket.

Acceptance:

- Local trace includes roleful target entries while preserving legacy
  `expectedTargets`.
- Prompt-debug inspector shows target roles.
- Session JSON remains backward compatible.
- Existing artifacts without roleful fields still read.

Tests:

- Trace serialization test.
- Prompt-debug inspector test.
- Session-store backward compatibility test.

### T609 - Deterministic E2E Regression Pack

Behavior/evidence ticket.

Add deterministic scenario coverage for the three live failures:

- Failure A: scoped `do not create extra files` must mutate requested file.
- Failure B: constraint filename must not become mutation obligation.
- Failure C: `scripts.js` / `styles.css` existing files must not be replaced by
  singular conventional names.

Acceptance:

- Scenarios use scripted LLM/tool outcomes, not live model dependence.
- Every scenario asserts final file state, trace contract, outcome
  classification, and absence of false success.
- No raw live transcripts are committed.

### T610 - Lane Closeout And Next-Move Decision

No runtime code unless a review fix is required.

Document:

- Which failures are fixed.
- Which tests now guard them.
- Remaining intent defects.
- Whether broader architecture/refactor work may resume.
- Whether a fresh live audit is warranted before more refactoring.

Stop condition:

- If T604-T609 are clean, the next move is a focused live audit of the same
  qwen/gpt-oss prompt shapes, not `AssistantTurnExecutor` refactoring.
- If any ticket exposes broader instability, stop and write a decision ticket
  before continuing.

## Verification Requirements

T600 verification:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Per behavior ticket:

1. Write RED test first.
2. Run the focused test and capture the expected failure.
3. Implement minimal production code.
4. Run the focused test and confirm GREEN.
5. Run neighboring focused suites:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.task.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
```

6. Run:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

7. PR CI must pass.
8. Beta push CI must pass.
9. Delete ticket branch/worktree only after beta push CI passes.

Live audit is not part of every ticket. Live audit happens after T610 if the
deterministic lane is clean.

## Out Of Scope

- No LLM intent advisor.
- No broad rewrite of `TaskContractResolver`.
- No one-off regex tail as the final architecture.
- No `AssistantTurnExecutor` refactor.
- No trace lifecycle/persistence changes before roleful intent behavior is
  protected.
- No raw live transcripts committed.
- No candidate version bump in this lane unless release packaging later asks
  for one.

## Confidence

High. The current source shape confirms the problem is structural: target
mentions are flattened before downstream policy needs to know their role. The
lane preserves the existing `TaskContract` compatibility boundary while adding
the missing typed model underneath it.
