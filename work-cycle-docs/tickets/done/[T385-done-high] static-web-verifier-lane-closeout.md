# [T385-done-high] Static Web Verifier Lane Closeout

Status: done
Priority: high
Date: 2026-05-23
Branch: `T385`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `1c65cbe2`
Predecessor: `T384`

## Scope

T385 is a no-code closeout and inspection ticket for the static-web verifier
extraction lane.

The task is to verify whether `StaticTaskVerifier` is now mostly facade and
orchestration for static-web verification after:

- `T376`: `WorkspaceOperationStaticVerifier`;
- `T378`: `StaticWebSelectorAnalyzer`;
- `T380`: `StaticWebSurfaceDetector`;
- `T383`: `StaticWebStructureVerifier`;
- `T384`: `StaticWebPartialVerifier`.

T385 intentionally does not extract another class. Source inspection found no
single remaining static-web verifier primitive that should move before the
lane is closed.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `1c65cbe2`:

| File | Lines | Current role |
|---|---:|---|
| `StaticTaskVerifier.java` | 1852 | Public verification facade, result selection, expectation verification, target verification, static-web orchestration, read-only diagnostic facades. |
| `WorkspaceOperationStaticVerifier.java` | 232 | Workspace operation postcondition verifier. |
| `StaticWebSurfaceDetector.java` | 205 | Static-web surface discovery, target-aware fallback, primary read completeness, primary HTML fallback. |
| `StaticWebSelectorAnalyzer.java` | 547 | HTML/CSS/JS selector/linkage/content facts and selector diagnostics. |
| `StaticWebStructureVerifier.java` | 167 | HTML structure, inline script/style facts, calculator/form structure primitives. |
| `StaticWebPartialVerifier.java` | 113 | Partial styled/functional static-web verification. |

The line count does not mean `StaticTaskVerifier` is clean globally. It is
still large. The relevant question for T385 is narrower: whether the
static-web verifier lane has extracted the obvious lower-level owners.

## Static-Web Ownership State

The static-web verifier boundary is now steady enough to stop this lane.

`StaticTaskVerifier` still owns static-web orchestration:

- selects `CapabilityProfile`;
- decides whether static-web verification is required;
- checks required HTML/CSS/JS mutation coverage for full web-app builds;
- selects obvious or target-aware primary static-web files;
- decides full verification versus partial styled/functional verification;
- aggregates static-web facts and problems into `TaskVerificationResult`;
- preserves public facade methods used by CLI/runtime consumers.

Extracted lower-level ownership is now coherent:

| Component | Owned responsibility |
|---|---|
| `StaticWebSurfaceDetector` | File-surface discovery and primary file selection primitives. |
| `StaticWebSelectorAnalyzer` | Full HTML/CSS/JS selector, linkage, placeholder, duplicate ID, and button/result facts. |
| `StaticWebStructureVerifier` | HTML structure, inline asset, and calculator/form structure primitives. |
| `StaticWebPartialVerifier` | Partial styled and partial functional static-web verification. |

The remaining static-web code in `StaticTaskVerifier` is mostly facade,
orchestration, and public read-only rendering glue.

## Important Negative Finding

`StaticTaskVerifier` as a whole is not mostly facade/orchestration.

It still directly owns several non-static-web verifier domains:

- task expectation dispatch and result-summary selection;
- literal exact-content verification;
- replacement verification and preserve-rest checks;
- append-line verification;
- bullet-list verification;
- exact edit evidence verification;
- source-derived artifact verification and source evidence extraction;
- expected/forbidden target verification;
- similar-target handling such as `script.js` versus `scripts.js`;
- generic mutation target readability/template-placeholder checks.

Therefore the correct conclusion is:

```text
Static-web verifier lane: close.
StaticTaskVerifier global cleanup: not finished.
```

Starting another static-web extraction would hide the real next ownership
problem, which is no longer static-web-specific.

## Remaining Static-Web Facades

These public static-web methods remain in `StaticTaskVerifier` by design:

- `obviousPrimaryFiles(...)`;
- `missingPrimaryReads(...)`;
- `renderSelectorInspection(...)`;
- `renderTargetAwareSelectorInspection(...)`;
- `renderStaticSelectorSearch(...)`;
- `renderWebDiagnostics(...)`;
- `renderScriptImportInspection(...)`;
- `currentWebDiagnostics(...)`.

Current consumers include:

- `AssistantTurnExecutor`;
- `ExecutionOutcome`;
- `RepairPolicy`;
- `ConditionalReviewFixPolicy`;
- `ToolCallRepromptStage`;
- `StaticTaskVerifierTest`.

Moving these public surfaces now would be an API/consumer rewiring ticket, not
a verifier primitive extraction. That should not be smuggled into the
static-web verifier closeout.

## Rejected Next Extractions

### Extract `StaticWebDiagnosticsRenderer`

Rejected for T385.

Reason: `renderWebDiagnostics(...)` and `currentWebDiagnostics(...)` are public
read-only facade surfaces used by runtime policy and tool-call reprompt code.
Moving them would require consumer rewiring and should be decided as a
diagnostic API lane, not as another verifier primitive burn-down.

### Extract `StaticWebScriptImportInspector`

Rejected for T385.

Reason: `renderScriptImportInspection(...)` is a read-only answer-rendering
surface tied to `StaticWebImportIntent`, expected-target extraction, and
current CLI answer behavior. It may become a future diagnostic component, but
it is not part of the static-web verifier primitive lane.

### Extract `StaticWebSelectorSearchRenderer`

Rejected for T385.

Reason: `renderStaticSelectorSearch(...)` is narrow and coherent, but it is a
read-only search renderer rather than verification ownership. Extracting it
would reduce line count without materially improving verifier architecture.

### Extract `verifySmallWebWorkspace(...)`

Rejected for T385.

Reason: that method is the remaining static-web orchestration point. Moving it
would simply rename the facade layer and would not remove a lower-level
ownership confusion.

## Decision

The static-web verifier extraction lane is closed for now.

The correct next hygiene lane is not another static-web ticket. It should be a
fresh inspection/decision ticket for the remaining non-static-web verifier
ownership in `StaticTaskVerifier`.

Best next decision target:

```text
[T386] StaticTaskVerifier Expectation And Evidence Boundary Decision
```

That ticket should inspect whether the next coherent owner is one of:

- `TaskExpectationStaticVerifier`;
- `SourceDerivedArtifactVerifier`;
- `ExactEditEvidenceVerifier`;
- `MutationTargetVerifier`.

Do not choose that implementation target before inspection. The current
evidence only proves that the remaining problem has moved out of the static-web
lane.

## Verification

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed.

```powershell
git diff --check
```

Result: passed.

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed.
