# [T382-done-high] Static Web Verification Boundary Closeout

Status: done
Priority: high
Date: 2026-05-23
Branch: `T382`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `6f4eade535adfab319eadf9da2f7010dbef00c74`
Predecessor: `T380`

## Scope

T382 is a closeout and decision ticket for the static-web verification
extraction lane after T376 through T380.

T382 does not change runtime behavior, verifier semantics, diagnostic wording,
repair prompts, final-answer wording, package-boundary rules, architecture
boundary rules, or the site documentation merged in T381.

The goal is to confirm whether the current static-web verification boundary is
steady enough to continue, and to choose the next implementation ticket from
source evidence rather than from mechanical class-count pressure.

## Current State

The active beta branch now contains these verification ownership slices:

| Ticket | Component | Current ownership |
|---|---|---|
| T376 | `WorkspaceOperationStaticVerifier` | Deterministic postconditions for copy, move, rename, delete, mkdir, write, and batch workspace operations. |
| T378 | `StaticWebSelectorAnalyzer` | HTML/CSS/JavaScript selector facts, linked asset discovery, placeholder checks, selector mismatch checks, and selector inspection rendering. |
| T380 | `StaticWebSurfaceDetector` | Static-web surface discovery, target-aware surface fallback, visible-file filtering, primary read completeness, preferred target selection, and primary HTML fallback. |
| Existing facade | `StaticTaskVerifier` | Public verifier facade, task verification result selection, exact content/edit/list/source-derived checks, static-web orchestration, partial web verification, read-only diagnostics, and import inspection rendering. |

Measured on T382:

- `StaticTaskVerifier.java`: 1952 lines.
- `StaticWebSelectorAnalyzer.java`: 505 lines.
- `StaticWebSurfaceDetector.java`: 184 lines.
- `WorkspaceOperationStaticVerifier.java`: 214 lines.

The line count still shows `StaticTaskVerifier` is large, but the important
metric is not size alone. The extracted classes now own coherent lower-level
concepts, while `StaticTaskVerifier` still acts as the compatibility and
orchestration facade for existing consumers.

## Source Evidence

The source inventory was taken from fresh `origin/v0.9.0-beta-dev` on branch
`T382`.

| Area | Evidence | Decision pressure |
|---|---|---|
| Prior decision | `work-cycle-docs/tickets/done/[T377-done-high] static-web-verifier-extraction-boundary-decision.md` rejected a broad static-web verifier extraction and chose selector facts first. | The lane should continue by extracting primitives, not by moving the whole verifier. |
| Selector extraction | `work-cycle-docs/tickets/done/[T378-done-high] extract-static-web-selector-analyzer.md` created `StaticWebSelectorAnalyzer` and kept `StaticTaskVerifier` as the public facade. | The analyzer boundary is stable and should not be reopened in T382. |
| Surface decision | `work-cycle-docs/tickets/done/[T379-done-high] static-web-surface-vs-partial-verification-decision.md` chose surface detection before partial verification. | T382 must now check whether partial verification is finally the correct next slice. |
| Surface extraction | `work-cycle-docs/tickets/done/[T380-done-high] extract-static-web-surface-detector.md` created `StaticWebSurfaceDetector` and explicitly did not move partial styled/functional verification. | Surface ownership is now clean enough to expose the next remaining primitive. |
| Static-web orchestration | `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` `verifySmallWebWorkspace(...)` selects the surface, decides full versus partial verification, invokes selector facts, and records facts/problems. | This remains orchestration and should stay in the facade until lower-level structure checks are separated. |
| Partial styled verification | `verifyPartialStyledWebWorkspace(...)` reads HTML, checks HTML structure, linked CSS, inline styles, and missing CSS files. | It depends on shared HTML structure and inline-style primitives rather than being a standalone domain yet. |
| Partial functional verification | `verifyPartialFunctionalWebWorkspace(...)` reads HTML, checks JavaScript presence, linked JavaScript, inline scripts, duplicate IDs, and calculator/form structure. | It depends on shared structure and form checks also used outside partial verification. |
| Shared HTML structure checks | `htmlStructureProblems(...)`, `malformedClosingTags(...)`, and `countCompleteTag(...)` are used by full static-web diagnostics and partial styled verification. | These are the real lower-level primitive, not partial verification itself. |
| Shared calculator/form checks | `calculatorFormProblems(...)`, `shouldExpectWeightHeightControls(...)`, `hasInputFor(...)`, and `hasResultOutput(...)` are used by full verification, read-only diagnostics, and partial functional verification. | Moving them into a `StaticWebPartialVerifier` would create false ownership because full diagnostics also depend on them. |
| Read-only diagnostics | `currentWebDiagnostics(...)` uses selector facts, HTML structure checks, and calculator/form checks. | Structure/form checks are part of false-success prevention, not only post-apply partial verification. |
| Public facade consumers | `AssistantTurnExecutor`, `ExecutionOutcome`, `RepairPolicy`, `ConditionalReviewFixPolicy`, and `ToolCallRepromptStage` still call `StaticTaskVerifier` facade methods. | Public consumer rewiring remains out of scope. The facade is intentional for now. |
| Tests | `StaticTaskVerifierTest` contains heavy static-web coverage for selector repair, BMI/form structure, self-contained pages, styled pages, diagnostics, and exact user-facing problem fragments. | Any next extraction must preserve exact current wording and use focused tests plus the existing verifier suite. |

## Decision

The static-web verification lane is in a steady incremental state, but it is
not finished.

Do not extract `StaticWebPartialVerifier` next.

The next implementation ticket should be:

```text
[T383] Extract static web structure verifier
```

Recommended component:

```text
src/main/java/dev/talos/runtime/verification/StaticWebStructureVerifier.java
```

This component should be package-private unless a future consumer proves that a
public API is needed.

## Why T383 Should Extract Structure First

After T380, the remaining question was whether partial styled/functional
verification had a clean boundary. It does not yet.

The partial methods are small enough to move, but their helper ownership is not
partial-specific:

- `htmlStructureProblems(...)` is used by partial styled verification and
  read-only/full diagnostics.
- `calculatorFormProblems(...)` is used by full static-web verification,
  read-only diagnostics, and partial functional verification.
- inline style and inline script checks support partial cases, but they are
  still structure facts about a single HTML document.

Therefore a direct `StaticWebPartialVerifier` extraction would either:

1. move shared structure/form checks into a misleading partial-only class;
2. leave structure/form helpers behind in `StaticTaskVerifier`, preserving the
   wrong ownership; or
3. extract too much behavior in one packet.

The correct lower-level primitive is static-web structure verification.

## T383 Boundary

T383 should move only structure and form primitives out of
`StaticTaskVerifier`.

T383 should create `StaticWebStructureVerifier` owning:

- HTML structure checks:
  - empty HTML detection;
  - malformed closing tag detection;
  - unclosed structural tag detection;
  - complete-tag counting.
- Inline asset presence facts:
  - nonblank inline `<script>` detection;
  - nonblank inline `<style>` detection.
- Calculator/form structure checks:
  - form or input container presence;
  - weight input detection when requested;
  - height input detection when requested;
  - submit/calculate button detection;
  - result output detection.

`StaticTaskVerifier` should continue to own:

- public facade methods;
- result status and summary selection;
- `verifySmallWebWorkspace(...)` orchestration;
- partial styled/functional verification orchestration;
- read-only diagnostic rendering;
- static selector search rendering;
- script import inspection rendering;
- `StaticWebCapabilityProfile` decisions.

T383 should not:

- move `verifyPartialStyledWebWorkspace(...)`;
- move `verifyPartialFunctionalWebWorkspace(...)`;
- move `currentWebDiagnostics(...)`;
- move `renderWebDiagnostics(...)`;
- move `renderScriptImportInspection(...)`;
- move `StaticWebImportIntent`;
- rewrite `AssistantTurnExecutor`, `ExecutionOutcome`, `RepairPolicy`,
  `ConditionalReviewFixPolicy`, or `ToolCallRepromptStage`;
- change exact user-facing fact/problem strings.

## T383 Test Shape

Recommended RED test:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebStructureVerifierTest" --no-daemon
```

Expected RED: compile/test failure because `StaticWebStructureVerifier` does
not exist.

Recommended focused GREEN tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebStructureVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

If read-only diagnostics or repair-facing facade methods are touched, also run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.policy.ConditionalReviewFixPolicyTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
```

Required closeout gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Rejected Moves

### Extract `StaticWebPartialVerifier` immediately

Rejected for T383.

Reason: the current partial verifier depends on structure/form checks that are
also used by full static-web verification and read-only diagnostics. Extracting
partial first would preserve the ownership confusion or give shared primitives
a misleading owner.

### Move public static-web facade methods off `StaticTaskVerifier`

Rejected for T383.

Reason: existing consumers depend on the facade for deterministic final-answer
overrides, repair context, outcome verification, conditional no-change review
answers, and tool-call reprompt diagnostics. Consumer rewiring should happen
only after the internal primitives are stable.

### Stop the static-web lane immediately

Rejected for now.

Reason: T382 found one clear remaining primitive: structure/form checks. That
is still within the verification and outcome truthfulness lane and can be
extracted without changing runtime behavior.

### Extract script import inspection next

Rejected for T383.

Reason: script import inspection depends on `StaticWebImportIntent` and answers
a specific read-only question. It is useful, but it is not the shared primitive
blocking partial verification cleanup.

## Acceptance Criteria

- T382 records the current static-web verification boundary after T376 through
  T380.
- T382 confirms `StaticTaskVerifier` remains an intentional public facade.
- T382 rejects a direct partial-verifier extraction with source evidence.
- T382 selects `StaticWebStructureVerifier` as the next implementation slice.
- T382 changes no runtime behavior.
- No generated artifacts, build outputs, or prompt-debug evidence directories
  are committed.

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
- Final post-ticket-update `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  passed (`BUILD SUCCESSFUL`, 1 actionable task: 1 up-to-date).
- Final post-ticket-update `.\gradlew.bat check --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 14 actionable tasks: 2 executed, 12 up-to-date).
