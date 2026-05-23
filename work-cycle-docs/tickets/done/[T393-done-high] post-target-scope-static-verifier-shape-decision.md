# [T393-done-high] Post Target Scope Static Verifier Shape Decision

Status: done
Priority: high
Date: 2026-05-23
Branch: `T393`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `4932ebdc`
Predecessor: `T392`

## Scope

T393 is a no-code inspection and decision ticket.

The task is to inspect the post-T392 shape of
`StaticTaskVerifier` before starting another extraction. T393 intentionally
does not move production code. The goal is to decide whether the next verifier
hygiene ticket has a real owner or whether another extraction would only chase
line count.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `4932ebdc`:

| File | Lines | Current role |
|---|---:|---|
| `StaticTaskVerifier.java` | 702 | Public verification facade, orchestration, final outcome selection, static-web verification orchestration, and static-web diagnostic facade methods. |
| `MutationTargetReadbackVerifier.java` | 122 | Mutation target readback, readable-target facts, template-placeholder checks. |
| `WorkspaceOperationStaticVerifier.java` | 232 | Workspace operation postcondition verification and alias/exemption facts. |
| `TargetScopeStaticVerifier.java` | 257 | Expected, forbidden, only-target, alias, exemption, and similar-target verification. |
| `TaskExpectationStaticVerifier.java` | 644 | Exact content, append-line, replacement, and bullet-count expectation verification. |
| `ExactEditReplacementVerifier.java` | 125 | Exact edit evidence replacement/preserve-rest verification. |
| `SourceDerivedArtifactVerifier.java` | 294 | Source-derived artifact verification and source evidence extraction. |
| `StaticWebSurfaceDetector.java` | 205 | Static-web file-surface discovery and primary file selection. |
| `StaticWebSelectorAnalyzer.java` | 547 | HTML/CSS/JS linkage, selector, duplicate-id, placeholder, and button/result facts. |
| `StaticWebStructureVerifier.java` | 167 | HTML structure and calculator/form structure checks. |
| `StaticWebPartialVerifier.java` | 113 | Partial styled/functional static-web verification. |

## Source Evidence

`StaticTaskVerifier.verifyInternal(...)` now delegates the main verification
domains:

| Evidence | Meaning |
|---|---|
| `StaticTaskVerifier.java:131` calls `MutationTargetReadbackVerifier.verify(...)`. | Target readback is no longer owned by the facade. |
| `StaticTaskVerifier.java:136` calls `WorkspaceOperationStaticVerifier.verify(...)`. | Workspace operation postconditions are no longer owned by the facade. |
| `StaticTaskVerifier.java:145` calls `TargetScopeStaticVerifier.verify(...)`. | T392 successfully moved target-scope ownership out of the facade. |
| `StaticTaskVerifier.java:154` calls `TaskExpectationStaticVerifier.verify(...)`. | Literal/task expectation checks are already delegated. |
| `StaticTaskVerifier.java:166` calls `ExactEditReplacementVerifier.verify(...)`. | Exact edit replacement evidence is already delegated. |
| `StaticTaskVerifier.java:170` calls `SourceDerivedArtifactVerifier.verify(...)`. | Source-derived artifact checks are already delegated. |

The remaining non-trivial ownership in `verifyInternal(...)` is result
adjudication:

- `StaticTaskVerifier.java:187` selects failure summaries by precedence across
  source-derived, exact-edit, replacement, append-line, bullet-count, exact
  content, and fallback problem summaries.
- `StaticTaskVerifier.java:206-239` selects the final passed/readback-only
  outcome across expectation, exact-edit, source-derived, static-web, and
  generic target/readback evidence.
- `StaticTaskVerifier.java:245-261` still owns problem classifier helpers used
  only by that outcome precedence block.
- `StaticTaskVerifier.java:695` still owns `firstProblemSummary(...)`.

`StaticTaskVerifier` also still owns static-web orchestration and diagnostic
facade APIs:

- `verifyPrimaryWebMutationCoverage(...)` starts at
  `StaticTaskVerifier.java:265`.
- `verifySmallWebWorkspace(...)` starts at `StaticTaskVerifier.java:287`.
- Public static-web facade methods start at `StaticTaskVerifier.java:371` and
  continue through `currentWebDiagnostics(...)` at
  `StaticTaskVerifier.java:543`.

Those static-web facade methods are externally consumed today:

| Consumer | StaticTaskVerifier surface |
|---|---|
| `AssistantTurnExecutor.java:4590` | `obviousPrimaryFiles(...)` |
| `AssistantTurnExecutor.java:4596` | `missingPrimaryReads(...)` |
| `AssistantTurnExecutor.java:4785` | `renderSelectorInspection(...)` |
| `AssistantTurnExecutor.java:4802` | `renderStaticSelectorSearch(...)` |
| `AssistantTurnExecutor.java:5049` | `renderWebDiagnostics(...)` |
| `AssistantTurnExecutor.java:5110` | `renderScriptImportInspection(...)` |
| `ConditionalReviewFixPolicy.java:84` | `currentWebDiagnostics(...)` |
| `RepairPolicy.java:156` | `renderTargetAwareSelectorInspection(...)` |
| `ToolCallRepromptStage.java:2561` | `renderWebDiagnostics(...)` |
| `ToolCallRepromptStage.java:2724` | `verifyWithoutTraceEvents(...)` |
| `ExecutionOutcome.java:341` | `verify(...)` |

## Decision

Do not make T393 another implementation extraction.

The post-T392 source proves that `StaticTaskVerifier` is now mostly a public
verification facade plus two remaining policy surfaces:

1. final outcome/result-summary adjudication;
2. static-web verification and read-only diagnostic facade methods.

The static-web diagnostic methods should not be moved next. T385 already
closed the static-web verifier extraction lane and explicitly identified these
methods as public facade/API surfaces, not lower-level verifier primitives.
Moving them now would be a consumer rewiring and diagnostic API ticket. That
may become valid later, but it is not the next correctness-driven slice.

The next coherent implementation owner is final outcome adjudication:

```text
[T394] Extract task verification outcome selector
```

That owner should be package-private under:

```text
src/main/java/dev/talos/runtime/verification/TaskVerificationOutcomeSelector.java
```

The owner should take typed verification flags/results and return the exact
same `TaskVerificationResult` currently selected by `StaticTaskVerifier`.

## T394 Boundary

T394 should extract only final outcome selection from `StaticTaskVerifier`.

T394 should move:

- failure summary precedence from `StaticTaskVerifier.java:187-203`;
- passed/readback-only summary precedence from `StaticTaskVerifier.java:206-239`;
- the outcome-only problem classifier helpers:
  - `isExactContentProblem(...)`;
  - `isAppendLineProblem(...)`;
  - `isReplacementProblem(...)`;
  - `isBulletCountProblem(...)`;
  - `firstProblemSummary(...)`.

T394 should not move:

- static-web diagnostics or render helpers;
- `verifySmallWebWorkspace(...)`;
- capability profile selection;
- mutation/readback aggregation;
- any expectation extraction rules;
- any wording in `TaskVerificationResult` summaries, facts, or problems.

The point is ownership, not line-count reduction. `StaticTaskVerifier` should
remain the public verification facade and the orchestrator that invokes
component verifiers.

## Rejected T393 Extractions

### Extract static-web diagnostics

Rejected for T393.

Reason: `renderWebDiagnostics(...)`, `currentWebDiagnostics(...)`,
`renderSelectorInspection(...)`, `renderStaticSelectorSearch(...)`, and
`renderScriptImportInspection(...)` are externally consumed by CLI/runtime
answer override, repair, policy, and reprompt code. Moving them is a
diagnostic API migration, not a verifier ownership primitive.

### Extract `verifySmallWebWorkspace(...)`

Rejected for T393.

Reason: the method is static-web orchestration. The lower-level static-web
owners already exist: surface detection, selector analysis, structure checks,
and partial verification. Moving this method alone would rename orchestration
without improving ownership.

### Extract `TaskExpectationStaticVerifier` internals

Rejected for T393.

Reason: `TaskExpectationStaticVerifier` is large, but T393 was scoped to the
post-T392 shape of `StaticTaskVerifier`. If expectation verification needs its
own lane, it should start with a separate source inspection instead of being
smuggled into this ticket.

## Acceptance Criteria

- T393 records the post-T392 `StaticTaskVerifier` source shape.
- T393 identifies that another immediate extraction was not assumed.
- T393 rejects static-web diagnostic movement as the next slice.
- T393 selects the next implementation owner only after source inspection.
- T393 changes no production runtime behavior.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 13 executed, 1 up-to-date).
