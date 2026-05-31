# [T623-done-high] Claim-scoped verification gate and static-web interaction guard

Status: done
Priority: high
Completed: 2026-06-01
Branch: v0.9.0-beta-dev
Base commit before implementation: `0404b392`
Talos version: `0.9.9`

## Problem

Talos could report a static-web mutation as verified when JavaScript was
syntactically valid and selectors existed, even if the requested interaction did
not perform the requested visible update.

The motivating T622 failure shape:

```js
document.getElementById('teaser-button').addEventListener('click', function() {
  document.getElementById('teaser-status').textC;
});
```

That code can pass syntax/readback/coherence checks while doing no useful DOM
update. Talos must not project that evidence into `COMPLETED_VERIFIED`.

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `STATIC_WEB`

Blocker level:

- release blocker class fixed for this static-web interaction shape

Why this level:

```text
False success after failed or missing verification is a release-blocking Talos
trust failure. The fix must reach the final completion status, not stop at a
static verifier summary.
```

## Architectural Result

Added the first shippable slice of the claim-scoped verification architecture:

- `VerificationVerdict`
- `ProofKind`
- `EvidenceAuthority`
- `EvidenceCoverage`
- `TargetBinding`
- `VerificationClaim`
- `VerificationObligation`
- `VerifierResult`
- `ClaimResult`
- `VerificationReport`
- `VerificationOutcomeGate`

Kept existing compatibility surfaces:

- `TaskVerificationStatus`
- `TaskVerificationResult`

The gate now enforces this invariant:

```text
Required claim obligations that are not sufficiently satisfied by
authoritative evidence cannot project to legacy PASSED.
```

## Implementation Summary

Runtime code:

- Added claim-scoped verification value types under
  `dev.talos.runtime.verification`.
- Added `VerificationOutcomeGate` so unsatisfied required obligations downgrade
  compatibility status instead of flattening to `PASSED`.
- Wired the report into `StaticTaskVerifier` and
  `TaskVerificationOutcomeSelector`.
- Added `StaticWebInteractionVerifier` for simple selector-bound click/update
  claims.
- Extended `StaticWebCapabilityProfile` so selector interaction tasks select
  the static-web verifier lane.
- Added `DocumentExtractionVerificationMapper` with explicit mappings for all
  `DocumentExtractionStatus` values.
- Fenced model-authored positive embedded verification text with a regression
  test.
- Tightened readback-only final-answer wording so an unsatisfied task-specific
  verifier is not described as "no task-specific verifier was applicable."

Static-web interaction guard behavior:

- Requires requested trigger/output selectors to be present or referenced.
- Requires a `click` handler bound to the requested trigger.
- Requires visible assignment to the requested output using `textContent` or
  `innerText`.
- Supports direct selector calls and simple aliases.
- Rejects wrong output target.
- Rejects wrong trigger binding.
- Does not create fake interaction obligations for pure selector-coherence
  repair prompts.

## Architecture Metadata

Capability:

- Static-web verification and claim-scoped verification evidence.

Operation(s):

- verify

Owning package/class:

- `dev.talos.runtime.verification`
- `dev.talos.runtime.capability.StaticWebCapabilityProfile`
- `dev.talos.runtime.outcome.StaticVerificationAnswerRenderer`
- `dev.talos.cli.modes.ExecutionOutcome`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: high outcome-truth risk.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: required static-web interaction claim must be satisfied
  before verified completion.
- Verification profile: static-web interaction guard added as a claim-scoped
  required obligation for matching tasks.
- Repair profile: unchanged.

Outcome and trace:

- Outcome/truth warnings: unsatisfied required interaction claim maps to
  unverified completion, not verified completion.
- Trace/debug fields: legacy verification summary records `READBACK_ONLY` with
  the unsatisfied interaction claim.

Refactor scope:

- Added a small verification spine and compatibility gate.
- Did not rewrite `ExecutionOutcome`.
- Did not remove existing static web coherence verification.

## Acceptance Evidence

The T622 no-op shape is now blocked:

- Static verifier result is not `PASSED`.
- `ExecutionOutcome` maps the turn to `COMPLETED_UNVERIFIED`.
- Final answer no longer says static verification passed.
- Embedded `[Static verification: passed - ...]` remains ignored by
  `EmbeddedStaticVerificationResultParser`.

Focused deterministic coverage:

- `requestedButtonStatusInteractionNoOpDoesNotPassStaticVerification`
- `requestedButtonStatusInteractionPassesWithTextContentAssignmentToBoundTarget`
- `requestedButtonStatusInteractionPassesWithInnerTextAssignmentToBoundTarget`
- `requestedButtonStatusInteractionRejectsAssignmentToWrongOutputTarget`
- `requestedButtonStatusInteractionRejectsHandlerBoundToWrongTrigger`
- `pureSelectorCoherenceRequestDoesNotCreateInteractionObligation`
- `staticWebCoherenceDoesNotVerifyRequestedButtonStatusInteractionNoOp`
- `ignoresEmbeddedStaticVerificationPassMarker`
- `mapsEveryDocumentExtractionStatusToVerificationVerdict`
- `VerificationOutcomeGateTest` authority and failure projection cases

## Focused Live Audit

Exploratory redirected-stdin TalosBench audit:

```text
Audit id: t623-live-audit-20260601-claim-gate-r2
Talos path: build/install/talos/bin/talos.bat
Model/backend observed: ollama/qwen2.5-coder:14b
Lane: SAFE_REDIRECTED_STDIN_EXPLORATORY
Approval: piped approval input allowed for this focused exploratory run
```

Artifacts:

- `local/manual-testing/t623-live-audit-20260601-claim-gate-r2/artifacts/20260601-003424/summary.md`
- `local/manual-testing/t623-live-audit-20260601-claim-gate-r2/artifacts/20260601-003424/t623-static-web-interaction-noop-unverified/transcript.txt`
- `local/manual-workspaces/t623-live-audit-20260601-claim-gate-r2/t623-static-web-interaction-noop-unverified/scripts.js`

Observed transcript evidence:

```text
Verification: READBACK_ONLY - Static interaction #teaser-button -> #teaser-status.
Required interaction verification was not satisfied.
Outcome: COMPLETE (COMPLETED_UNVERIFIED)
```

Final workspace state:

```js
document.getElementById('teaser-button').addEventListener('click', function() { document.getElementById('teaser-status').textC; });
```

Limit:

```text
This live audit is approval-sensitive and used redirected approval input, so it
is not synchronized approval release-gate evidence. It is a focused exploratory
runtime check in addition to deterministic regression coverage.
```

## Verification Commands

Executed during the T623 closeout:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.capability.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.outcome.StaticVerificationAnswerRendererTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest.staticWebCoherenceDoesNotVerifyRequestedButtonStatusInteractionNoOp" --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat installDist --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -CasesPath local\manual-testing\t623-live-audit-20260601-claim-gate\talosbench-t623-cases.json -CaseId t623-static-web-interaction-noop-unverified -TalosPath .\build\install\talos\bin\talos.bat -IncludeManualRequired -AllowPipedApprovalInputs -StrictEvidence -AuditId t623-live-audit-20260601-claim-gate-r2 -ModelLabel local-config -Lane SAFE_REDIRECTED_STDIN_EXPLORATORY -TranscriptRoot local\manual-testing\t623-live-audit-20260601-claim-gate-r2\artifacts -WorkspaceRoot local\manual-workspaces\t623-live-audit-20260601-claim-gate-r2
.\gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/t623-live-audit-20260601-claim-gate-r2,local/manual-workspaces/t623-live-audit-20260601-claim-gate-r2" --no-daemon
.\gradlew.bat check --no-daemon
```

## Non-Goals

- Did not add browser/runtime verification.
- Did not add OCR, render, image, PowerPoint, or layout verification.
- Did not give LLM advisory evidence any authority to raise a claim to
  verified.
- Did not remove the legacy `TaskVerificationResult` compatibility surface.
- Did not make the static interaction guard a JavaScript semantic analyzer.

## Known Follow-Ups

- T624: first-class `VerificationReport` in `ExecutionOutcome`.
- T625: static-web browser behavior verifier lane.
