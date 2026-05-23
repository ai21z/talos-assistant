# [T395-done-high] Close StaticTaskVerifier Facade Lane

Status: done
Priority: high
Date: 2026-05-24
Branch: `T395`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `5bc179f1`
Predecessor: `T394`

## Scope

T395 is a no-code inspection and decision ticket.

The task is to inspect the post-T394 shape of `StaticTaskVerifier` before
choosing another ticket. T395 intentionally does not extract another class.
The goal is to decide whether `StaticTaskVerifier` still has a concrete
ownership problem, or whether continuing to cut pieces from it would now be
line-count chasing.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `5bc179f1`:

| File | Lines | Current role |
|---|---:|---|
| `StaticTaskVerifier.java` | 621 | Public verification facade/orchestrator, static-web verification orchestration, and static-web diagnostic facade API. |
| `TaskVerificationOutcomeSelector.java` | 120 | Final static-verification status/summary selector extracted by T394. |
| `MutationTargetReadbackVerifier.java` | 122 | Mutation target readback, readable-target facts, and template-placeholder checks. |
| `WorkspaceOperationStaticVerifier.java` | 232 | Workspace operation postcondition verification and target alias/exemption facts. |
| `TargetScopeStaticVerifier.java` | 257 | Expected, forbidden, only-target, alias, exemption, and similar-target verification. |
| `TaskExpectationStaticVerifier.java` | 644 | Literal, replacement, append-line, and bullet-list expectation verification plus trace recording. |
| `ExactEditReplacementVerifier.java` | 125 | Exact edit evidence replacement/preserve-rest verification. |
| `SourceDerivedArtifactVerifier.java` | 294 | Source-derived artifact verification and source evidence extraction. |
| `StaticWebSurfaceDetector.java` | 205 | Static-web file-surface discovery and primary file selection. |
| `StaticWebSelectorAnalyzer.java` | 547 | HTML/CSS/JS linkage, selector, duplicate-id, placeholder, and button/result facts. |
| `StaticWebStructureVerifier.java` | 167 | HTML structure and calculator/form structure checks. |
| `StaticWebPartialVerifier.java` | 113 | Partial styled/functional static-web verification. |

## Source Evidence

`StaticTaskVerifier.verifyInternal(...)` now delegates all major non-web
verification ownership:

| Evidence | Meaning |
|---|---|
| `StaticTaskVerifier.java:131` calls `MutationTargetReadbackVerifier.verify(...)`. | Target readback is delegated. |
| `StaticTaskVerifier.java:136` calls `WorkspaceOperationStaticVerifier.verify(...)`. | Workspace operation checks are delegated. |
| `StaticTaskVerifier.java:145` calls `TargetScopeStaticVerifier.verify(...)`. | Target-scope checks are delegated. |
| `StaticTaskVerifier.java:154` calls `TaskExpectationStaticVerifier.verify(...)`. | Task expectation checks are delegated. |
| `StaticTaskVerifier.java:161` calls `ExactEditReplacementVerifier.verify(...)`. | Exact edit evidence checks are delegated. |
| `StaticTaskVerifier.java:165` calls `SourceDerivedArtifactVerifier.verify(...)`. | Source-derived artifact checks are delegated. |
| `StaticTaskVerifier.java:181` calls `TaskVerificationOutcomeSelector.select(...)`. | Final status/summary selection is delegated. |

The remaining `StaticTaskVerifier` responsibilities are:

- public `verify(...)` and `verifyWithoutTraceEvents(...)` facade methods;
- local aggregation of verifier facts, problems, and mutated targets;
- capability profile selection;
- static-web mutation coverage;
- static-web verification orchestration;
- public read-only static-web diagnostic facade methods.

This is now a defensible facade/orchestration role.

## Static-Web Diagnostic Surface

Static-web diagnostic movement remains rejected for now.

The public static-web facade methods are still consumed outside
`StaticTaskVerifier`:

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

Moving these methods now would be a diagnostic API migration across CLI,
policy, repair, reprompt, and execution-outcome code. That is not a
verification primitive extraction and should not be done as a casual T395
implementation.

## Decision

Close the `StaticTaskVerifier` extraction lane for now.

Do not extract another random piece from `StaticTaskVerifier`.
Do not move static-web diagnostic facade methods.
Do not move `verifySmallWebWorkspace(...)` merely to reduce the file size.

The next ownership problem has moved elsewhere. The largest mixed verifier is
now `TaskExpectationStaticVerifier.java`, which owns several distinct
expectation domains:

- literal exact-content expectation verification;
- replacement expectation verification;
- preserve-rest replacement evidence checks;
- append-line expectation verification;
- append-line mutation evidence checks;
- bullet-list count verification;
- expectation trace recording;
- path normalization shared by expectation checks.

Those are not automatically safe to split. They are related by the shared
`TaskExpectationResolver` input, the shared `Result` contract, and
`LocalTurnTraceCapture` evidence recording. The next ticket should inspect
that boundary before any implementation.

## Next Ticket

The next correct ticket is:

```text
[T396] TaskExpectationStaticVerifier Boundary Decision
```

T396 should be a no-code or mostly no-code inspection ticket. It should decide
whether expectation verification should split by expectation kind, by evidence
recording responsibility, or remain centralized until a stronger reason
appears.

T396 should inspect:

- `TaskExpectationStaticVerifier.java`;
- `TaskExpectationStaticVerifierTest.java`;
- expectation model classes under `dev.talos.runtime.expectation`;
- `TaskExpectationResolver`;
- `LocalTurnTraceCapture.recordExpectationVerified(...)`;
- `StaticTaskVerifierTest` cases that assert exact content, replacement,
  append-line, and bullet-list wording.

T396 should not start by extracting literal, replacement, append-line, or
bullet-list code until it proves the first split preserves trace behavior and
wording without duplicating path and mutation-evidence logic.

## Rejected T395 Implementations

### Extract static-web diagnostics

Rejected.

Reason: this is a public diagnostic API migration, not a verifier primitive
ownership fix. It touches CLI answer overrides, repair policy, conditional
review policy, reprompt behavior, and execution outcome consumers.

### Extract `verifySmallWebWorkspace(...)`

Rejected.

Reason: it is the remaining static-web orchestration point. The lower-level
static-web owners already exist. Moving it would mostly rename orchestration.

### Extract another helper from `StaticTaskVerifier`

Rejected.

Reason: after T394, the remaining helpers support facade/orchestration and
diagnostic API behavior. Extracting them without a named consumer boundary
would be architecture theater.

### Start `TaskExpectationStaticVerifier` implementation immediately

Rejected for T395.

Reason: the expectation verifier is a plausible next lane, but it mixes
expectation-kind checks, mutation evidence, trace recording, and exact wording.
That needs a boundary decision before code movement.

## Acceptance Criteria

- T395 records the post-T394 shape of `StaticTaskVerifier`.
- T395 explicitly closes the current `StaticTaskVerifier` extraction lane.
- T395 keeps static-web diagnostic movement rejected for now.
- T395 identifies `TaskExpectationStaticVerifier` as the next inspection lane,
  not as an immediate implementation target.
- T395 changes no production runtime behavior.
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
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; first run
  had 14 actionable tasks: 13 executed, 1 up-to-date; final packet rerun had
  14 actionable tasks: 2 executed, 12 up-to-date).
