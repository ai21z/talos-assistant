# [T379-done-high] Static Web Surface Vs Partial Verification Decision

Status: done
Priority: high
Date: 2026-05-23
Branch: `T379`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `2d7fbc0703c6c28def243fdc96e91d28fccfe706`
Predecessor: `T378`

## Scope

T379 is an inspection and decision ticket. It pauses after the
`StaticWebSelectorAnalyzer` extraction and re-inspects the remaining
`StaticTaskVerifier` static-web responsibilities before choosing the next
implementation slice.

T379 does not change production runtime behavior, verifier semantics,
diagnostic wording, final-answer wording, repair prompts, package-boundary
rules, or architecture-boundary rules.

## Source Evidence

The source inventory was taken from fresh `origin/v0.9.0-beta-dev` on branch
`T379`.

| Area | Current evidence | Ownership pressure |
|---|---|---|
| Prior lane decision | `work-cycle-docs/tickets/done/[T375-done-high] verification-and-outcome-truthfulness-ownership-decision.md` selected verification and outcome truthfulness ownership as the active lane. | T379 must improve verifier ownership without weakening runtime-owned truthfulness checks. |
| Static-web boundary decision | `work-cycle-docs/tickets/done/[T377-done-high] static-web-verifier-extraction-boundary-decision.md` rejected a broad static-web verifier extraction and selected a first analyzer slice. | T379 should continue incremental extraction, not collapse all remaining web behavior into one packet. |
| First static-web extraction | `work-cycle-docs/tickets/done/[T378-done-high] extract-static-web-selector-analyzer.md` created `StaticWebSelectorAnalyzer` and kept `StaticTaskVerifier` as the public facade. | Selector/linkage facts are now separated. The next decision is whether to extract surface discovery or partial verification. |
| Historical architecture report | `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md` lists `StaticWebSurfaceDetector`, `StaticWebFacts`, and `StaticWebVerifier` as distinct follow-up concepts under VRT-001. | The historical map already separates surface detection from verifier semantics. T379 should respect that split unless current source contradicts it. |
| Static-web orchestration | `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` `verifySmallWebWorkspace(...)` first selects primary files, optionally falls back to target-aware files, chooses partial styled/functional paths, and then delegates selector facts to `StaticWebSelectorAnalyzer`. | This method is an orchestrator over at least three concepts: surface selection, partial verification, and full HTML/CSS/JS fact evaluation. |
| Surface discovery group | `StaticTaskVerifier.obviousPrimaryFiles(...)`, `targetAwarePrimaryFiles(...)`, `visibleRegularFiles(...)`, `webFileNames(...)`, `hasVisibleWebTarget(...)`, `isSmallWorkspaceWebFile(...)`, `preferredWebTargetFiles(...)`, `missingPrimaryReads(...)`, `primaryHtmlTargets(...)`, and `hasPrimaryWebSurface(...)` decide what static-web files form the current surface. | These methods are mostly discovery and normalization. They are reused by post-apply verification, read-only diagnostics, repair facts, script-import inspection, and inspection completeness checks. |
| Read-only facades | `renderSelectorInspection(...)`, `renderTargetAwareSelectorInspection(...)`, `renderStaticSelectorSearch(...)`, `renderWebDiagnostics(...)`, and `currentWebDiagnostics(...)` all depend on surface discovery before rendering deterministic evidence. | Surface detection is not only a post-apply verifier concern. Moving it behind a focused component preserves the public facade while reducing duplicated discovery logic. |
| External consumers | `AssistantTurnExecutor` calls `StaticTaskVerifier.obviousPrimaryFiles(...)`, `missingPrimaryReads(...)`, `renderSelectorInspection(...)`, `renderStaticSelectorSearch(...)`, and `renderWebDiagnostics(...)`. `RepairPolicy` calls `renderTargetAwareSelectorInspection(...)`. `ConditionalReviewFixPolicy` calls `currentWebDiagnostics(...)`. | The public facade should remain stable. The extraction should be internal first, with consumer rewiring deferred unless source evidence later proves it necessary. |
| Partial styled verification | `verifyPartialStyledWebWorkspace(...)` reads HTML, checks HTML structure, linked CSS, inline styles, existing filenames, and emits exact user-facing facts/problems. | This is verifier behavior, not pure discovery. Moving it first would mix architecture cleanup with semantic verification wording. |
| Partial functional verification | `verifyPartialFunctionalWebWorkspace(...)` reads HTML, checks JavaScript presence, linked JavaScript, inline scripts, duplicate IDs, calculator/form structure, and emits exact user-facing facts/problems. | This is higher-risk than surface detection because it owns failure criteria for one-file and partial web tasks. |
| Capability-profile predicates | `StaticWebCapabilityProfile.looksStyledWebTask(...)`, `looksFunctionalWebTask(...)`, `looksCalculatorOrFormTask(...)`, and `TargetSurface.allowsFunctionalPartial()` determine whether partial web verification should run. | Partial verification is coupled to task-intent semantics. Extracting it before surface detection would not be a purely mechanical class split. |
| Existing tests | `StaticTaskVerifierTest` covers partial styled failures/passes, self-contained HTML, target-aware surface refusal, read-only diagnostics, selector repair, button-result diagnostics, and exact output fragments. `AssistantTurnExecutorTest`, `RepairPolicyTest`, and `ConditionalReviewFixPolicyTest` cover facade consumers. | The tests show that surface discovery is shared infrastructure and partial verification is behavior-sensitive. A decision-only T379 avoids changing these semantics without a sharper implementation boundary. |

## Decision

Do not implement a production extraction in T379.

The next implementation ticket should extract static-web surface detection
before extracting partial web verification.

Recommended next ticket:

```text
[T380] Extract static web surface detector
```

Recommended component:

```text
src/main/java/dev/talos/runtime/verification/StaticWebSurfaceDetector.java
```

The new component should be package-private unless tests or future consumers
prove a public API is necessary.

## Why Surface Detection Comes First

Surface detection is the lower-level shared concept.

It answers:

- Which visible root files are eligible static-web files?
- Is this a small enough workspace for deterministic static-web checks?
- Which primary HTML/CSS/JavaScript files should be considered?
- Do read-paths already cover the primary surface?
- Do target hints justify target-aware fallback in a mixed workspace?
- Which primary HTML file should script-import inspection inspect when the
  user did not name one?

Partial verification is downstream of those answers. It decides whether a
partial surface is sufficient for a styled or functional request and emits
facts/problems. That is verifier behavior, not discovery infrastructure.

Extracting the detector first has a better reliability-to-complexity ratio:

- it preserves the current `StaticTaskVerifier` public facade;
- it preserves exact diagnostic and verifier wording;
- it isolates file discovery without moving task-intent predicates;
- it gives later partial-verifier extraction a smaller dependency surface;
- it gives direct tests for target-aware surface selection and read-completeness
  behavior that are currently only indirect through `StaticTaskVerifierTest`.

## Rejected Next Slice

### Extract partial web verification first

Rejected for T380.

Reason: partial styled/functional verification is coupled to capability-profile
intent predicates, `TargetSurface`, HTML structure checks, inline style/script
presence, linked asset checks, duplicate ID checks, calculator/form heuristics,
facts, problems, and exact user-facing wording.

That extraction is valid later, but doing it before a detector would keep the
partial verifier dependent on private surface-selection methods in
`StaticTaskVerifier` or force a broader move than the ticket needs.

### Move public facade methods to the new detector immediately

Rejected for T380.

Reason: `AssistantTurnExecutor`, `RepairPolicy`, and
`ConditionalReviewFixPolicy` currently rely on `StaticTaskVerifier` as a stable
runtime-owned facade for deterministic evidence. T380 should change internal
ownership first and leave public consumers untouched.

### Extract static-web import inspection first

Rejected for T380.

Reason: `renderScriptImportInspection(...)` answers a specific read-only
import question through `StaticWebImportIntent`. It does use primary HTML
surface selection, but it is not the primary ownership problem after T378.

## T380 Implementation Boundary

T380 should:

- create `StaticWebSurfaceDetector` under `dev.talos.runtime.verification`;
- move direct surface discovery helpers out of `StaticTaskVerifier`;
- keep public facade methods on `StaticTaskVerifier`;
- delegate `obviousPrimaryFiles(...)` and `missingPrimaryReads(...)` through the
  detector;
- delegate target-aware selection and primary-surface checks internally;
- delegate primary HTML fallback for script-import inspection if it can be done
  without touching `StaticWebImportIntent`;
- add direct detector tests for obvious primary files, target-aware fallback,
  too-large mixed workspaces, primary read completeness, and primary HTML
  selection;
- keep integration coverage through `StaticTaskVerifierTest`.

T380 should not:

- move `verifyPartialStyledWebWorkspace(...)`;
- move `verifyPartialFunctionalWebWorkspace(...)`;
- change `StaticWebCapabilityProfile`;
- change `TargetSurface`;
- change `renderWebDiagnostics(...)` output;
- change repair prompt wording;
- change final-answer wording;
- rewrite `AssistantTurnExecutor`, `RepairPolicy`, or
  `ConditionalReviewFixPolicy`;
- change static-web import intent semantics.

## T380 Focused Test Plan

Recommended RED test:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --no-daemon
```

Expected RED: compile/test failure because `StaticWebSurfaceDetector` does not
exist yet.

Recommended focused GREEN tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

If facade methods are touched beyond direct delegation, also run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.policy.ConditionalReviewFixPolicyTest" --no-daemon
```

Required closeout gates for T380:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Provisional Follow-Up

After T380 lands, re-inspect before choosing T381.

The likely next implementation target is either:

- `StaticWebPartialVerifier`, if surface detection extraction leaves partial
  styled/functional verification with a clean data-in/data-out boundary; or
- `StaticWebStructureVerifier`, if HTML structure, inline script/style, and
  calculator/form checks prove to be the real lower-level primitive.

Do not choose that ticket until T380 has landed and the remaining
`StaticTaskVerifier` shape is rechecked.

## Acceptance Criteria

- T379 records source evidence for the next static-web extraction order.
- T379 rejects partial web verification as the immediate next implementation
  slice with concrete source reasons.
- T379 chooses T380 as static-web surface detection extraction.
- T379 changes no runtime behavior.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 1 actionable task: 1 executed).
- `git diff --check`: passed.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`, 14
  actionable tasks: 4 executed, 10 up-to-date).
- Final post-ticket-update `.\gradlew.bat check --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 14 actionable tasks: 2 executed, 12 up-to-date).
