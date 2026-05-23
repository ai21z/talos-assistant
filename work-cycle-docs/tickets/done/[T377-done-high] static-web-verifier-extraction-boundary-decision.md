# [T377-done-high] Static Web Verifier Extraction Boundary Decision

Status: done
Priority: high
Date: 2026-05-23
Branch: `T377`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `95567e4eead11e43bf3d1e5c70f5e32c02da29fe`
Predecessor: `T376`

## Scope

This is an inspection and decision ticket, not an implementation burn-down.

T377 starts from fresh beta after T376 and inspects the static-web verification
extraction boundary before touching production code. It records the current
source shape, rejects a broad static-web verifier extraction, and chooses the
next implementation slice from source evidence.

T377 does not change runtime behavior, verifier semantics, final-answer wording,
repair prompts, package-boundary rules, or architecture-boundary scanner rules.

## Source Evidence

The source inventory was taken from fresh `origin/v0.9.0-beta-dev` on branch
`T377`.

| Area | Current evidence | Ownership pressure |
|---|---|---|
| Prior lane decision | `work-cycle-docs/tickets/done/[T375-done-high] verification-and-outcome-truthfulness-ownership-decision.md` selected verification and outcome truthfulness ownership. | Static-web extraction belongs to the active lane, but must preserve truthfulness and output behavior. |
| First implementation slice | `work-cycle-docs/tickets/done/[T376-done-high] extract-workspace-operation-static-verifier.md` extracted workspace-operation verification while keeping `StaticTaskVerifier.verify(...)` stable. | T377 should continue the same discipline: inspect the next verifier unit before changing code. |
| Original architecture finding | `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md` lists `StaticTaskVerifier` as VRT-001 and proposes `StaticWebSurfaceDetector`, `StaticWebFacts`, and `StaticWebVerifier` as later extraction targets. | The historical plan already separates surface detection, facts, and verifier ownership. A one-shot extraction would ignore that sequence. |
| Static-web entrypoint | `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` owns `verifyPrimaryWebMutationCoverage(...)` and `verifySmallWebWorkspace(...)` around the post-apply verifier path. | These methods are verifier behavior, but they also call capability-profile predicates and mutate the shared facts/problems result flow. |
| Read-only diagnostics | `StaticTaskVerifier.renderWebDiagnostics(...)` and `currentWebDiagnostics(...)` render deterministic read-only static-web diagnostics. | Static-web logic is not only post-apply verification. It also protects read-only answer truthfulness. |
| Selector repair facts | `StaticTaskVerifier.renderSelectorInspection(...)`, `renderTargetAwareSelectorInspection(...)`, `renderStaticSelectorSearch(...)`, and `missingPrimaryReads(...)` are public helpers consumed outside the verifier path. | Moving public helpers immediately would touch answer override, repair context, and inspection completeness behavior in one packet. |
| Selector facts internals | `SelectorFacts`, selector regexes, linkage checks, content checks, button/result checks, and diagnostic rendering live inside `StaticTaskVerifier`. | This is the cleanest extraction seam: a lower-level static-web facts/analyzer component can own parsing and facts while the public facade stays stable. |
| HTML structure and partial-web checks | `htmlStructureProblems(...)`, `verifyPartialStyledWebWorkspace(...)`, and `verifyPartialFunctionalWebWorkspace(...)` cover partial styled/functional web tasks. | These are adjacent to selector facts, but not identical. Moving them with selector facts would widen the first implementation ticket. |
| CLI answer overrides | `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java` calls `StaticTaskVerifier.missingPrimaryReads(...)`, `renderSelectorInspection(...)`, `renderStaticSelectorSearch(...)`, `renderWebDiagnostics(...)`, and `renderScriptImportInspection(...)`. | The CLI currently depends on `StaticTaskVerifier` as a stable facade for deterministic final-answer overrides. That facade should not be broken in the first static-web slice. |
| Conditional review policy | `src/main/java/dev/talos/runtime/policy/ConditionalReviewFixPolicy.java` calls `StaticTaskVerifier.currentWebDiagnostics(...)` and uses `WebDiagnostics` to produce no-change review answers. | Static-web diagnostics are part of false-success prevention and no-change truthfulness. Their exact behavior must remain stable. |
| Repair policy | `src/main/java/dev/talos/runtime/repair/RepairPolicy.java` calls `StaticTaskVerifier.renderTargetAwareSelectorInspection(...)` to enrich selector repair instructions. | Repair prompt enrichment depends on exact current selector fact wording. Changing this while moving verifier ownership would increase repair-loop risk. |
| Outcome path | `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` invokes `StaticTaskVerifier.verify(...)` for post-apply verification. | Static-web extraction must keep the post-apply verification entrypoint stable until a narrower component is proven. |
| Tests | `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java` has static-web post-apply and read-only diagnostics coverage, including exact selector/linkage/button/form wording. | The tests show heavy behavior coupling. A broad extraction would risk changing release-gate wording while claiming to be architecture-only. |

## Decision

Do not extract a full static-web verifier in T377.

The static-web code has three responsibilities that should not be moved at once:

1. Post-apply static-web verification for mutation outcomes.
2. Read-only diagnostics and deterministic answer overrides.
3. Repair-context selector facts and search evidence.

The next implementation ticket should be:

```text
[T378] Extract static web selector facts analyzer
```

T378 should create a package-local static-web facts/analyzer component under:

```text
src/main/java/dev/talos/runtime/verification/
```

Recommended class name:

```text
StaticWebSelectorAnalyzer
```

The new component should own only the pure selector/linkage/content analysis
boundary:

- HTML class and ID extraction.
- Linked CSS and JavaScript discovery.
- Preferred linked/target-aware CSS and JavaScript selection.
- CSS class, ID, and bare-element selector extraction.
- JavaScript class and ID extraction.
- Placeholder/content checks for HTML, CSS, and JavaScript.
- Duplicate/missing linked asset checks.
- Selector mismatch checks.
- Generic button-result diagnostic checks.
- Rendering of the current selector inspection text.

`StaticTaskVerifier` should remain the public facade in T378. Existing public
methods should delegate where useful but keep their names and output strings:

- `renderSelectorInspection(...)`
- `renderTargetAwareSelectorInspection(...)`
- `renderWebDiagnostics(...)`
- `currentWebDiagnostics(...)`
- `missingPrimaryReads(...)`
- `verifySmallWebWorkspace(...)`

## Why T378 Is The Correct Next Slice

T378 is the correct next implementation slice because it removes real ownership
confusion without changing the outcome contract.

Concrete reasons:

- Selector/linkage facts are already internally grouped as `SelectorFacts`.
- The analyzer boundary is local, deterministic, and file-content based.
- The current public API can stay on `StaticTaskVerifier`, limiting consumer
  churn.
- Read-only diagnostics, repair enrichment, and post-apply verification can all
  reuse the extracted facts without moving their orchestration yet.
- The existing exact-string tests can prove behavior preservation.

This is not an architecture-baseline ticket. The architecture baseline is zero.
The metric is now internal verifier ownership clarity plus unchanged
truthfulness behavior.

## Rejected Moves

### Full `StaticWebVerifier` extraction

Rejected for T377 and T378.

Reason: static-web behavior currently spans post-apply verification, read-only
diagnostics, repair-context enrichment, selector search, script-import
inspection, capability-profile predicates, and target-aware file discovery. A
single PR that moves all of this would be a broad semantic refactor, not a
controlled extraction.

### Move public helper APIs first

Rejected for T378.

Reason: `AssistantTurnExecutor`, `ConditionalReviewFixPolicy`,
`RepairPolicy`, and `ExecutionOutcome` currently rely on `StaticTaskVerifier`
as a stable facade. Moving the public API first would combine internal
ownership cleanup with consumer rewiring and final-answer behavior risk.

### Start with static-web import inspection

Rejected for T378.

Reason: `renderScriptImportInspection(...)` uses `StaticWebImportIntent` and
answers a different read-only question: whether a requested JavaScript file is
imported by HTML. That is adjacent to selector diagnostics, but it is not the
same extraction boundary.

### Start with partial styled/functional web verification

Rejected for T378.

Reason: partial styled/functional checks use HTML structure, inline style/script
presence, form heuristics, and capability-profile predicates. They can follow
after selector facts are isolated, but moving them first would blur the facts
boundary.

### Change final-answer or repair wording

Rejected for T378.

Reason: this lane is about verifier ownership, not user-visible copy changes.
Existing exact-string tests should remain valid unless they reveal a current
false claim.

## T378 Implementation Boundary

T378 should:

- Add `StaticWebSelectorAnalyzer` under `dev.talos.runtime.verification`.
- Move the private selector/linkage/content analyzer data and helper logic out
  of `StaticTaskVerifier`.
- Keep the extracted type package-private unless a test proves public access is
  needed.
- Keep `StaticTaskVerifier` as the public facade for existing consumers.
- Add direct analyzer tests for selector/linkage facts.
- Keep integration coverage through `StaticTaskVerifierTest`.
- Preserve exact current problem/fact/diagnostic strings.

T378 should not:

- Move `StaticWebImportIntent`.
- Rewrite `AssistantTurnExecutor`.
- Rewrite `ConditionalReviewFixPolicy`.
- Rewrite `RepairPolicy`.
- Change `ExecutionOutcome`.
- Change static-web capability profile classification.
- Change repair-loop routing.
- Change final-answer wording.
- Extract all of `verifySmallWebWorkspace(...)`.

## T378 Focused Test Plan

Recommended RED test:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --no-daemon
```

Expected RED: compile/test failure because `StaticWebSelectorAnalyzer` does not
exist yet.

Recommended focused GREEN tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

If any public diagnostics or repair-context rendering path is touched, also
run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.policy.ConditionalReviewFixPolicyTest" --no-daemon
```

Required closeout gates for T378:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- T377 records source evidence for the static-web extraction boundary.
- T377 changes no production runtime behavior.
- T377 rejects a broad static-web verifier extraction with concrete reasons.
- T377 chooses a concrete next implementation slice.
- T377 preserves the current `StaticTaskVerifier` public facade.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Result:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 1 actionable task: 1 executed).
- `git diff --check`: passed.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`, 14
  actionable tasks: 4 executed, 10 up-to-date).
