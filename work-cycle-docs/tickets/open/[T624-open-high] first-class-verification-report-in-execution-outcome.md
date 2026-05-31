# [T624-open-high] First-class VerificationReport in ExecutionOutcome

Status: open
Priority: high
Created: 2026-06-01
Branch: v0.9.0-beta-dev
Predecessor: T623

## Evidence Summary

- Source: T623 implementation review and architecture follow-up.
- Talos version / commit at creation: `talosVersion=0.9.9`, predecessor base `0404b392`.
- Model/backend: none; static code and deterministic test evidence only.
- Workspace fixture: not applicable.
- Verification status: follow-up ticket only.

## Problem

T623 added the claim-scoped verification spine and used its compatibility
projection to prevent static-web interaction overclaims. That is the right first
slice, but the rich `VerificationReport` still terminates inside static
verification and is projected into legacy `TaskVerificationResult` before
`ExecutionOutcome` records final outcome evidence.

That is acceptable for T623 because it closes the false `COMPLETED_VERIFIED`
path, but it is not the final architecture. Future verifier lanes need
downstream access to claim results, proof kind, authority, coverage, target
binding, limitations, and obligation sufficiency without reverse-engineering
legacy summaries.

Post-T623 review added two concrete requirements:

- The T622-style `.textC;` no-op currently downgrades through `UNVERIFIED` /
  `READBACK_ONLY`, not `FAILED`. That conservative verdict is acceptable for a
  non-executing static lane, but the rich report should still surface the
  specific static limitation/problem line so the user sees why the claim was not
  verified.
- `EmbeddedStaticVerificationResultParser` is currently failure-only and T623
  added a positive-pass ignore regression, but the architectural invariant is
  still implicit. T624 must model embedded model-authored verification text as
  advisory or negative-only compatibility evidence. It must never satisfy a
  required obligation or raise an outcome to verified when post-apply
  verification is skipped.

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `TRACE_REDACTION`

Blocker level:

- candidate follow-up

Why this level:

```text
T623 closes the immediate false-success bug, but future verifier expansion
needs a first-class report boundary before more artifact kinds are added.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add more strings to TaskVerificationResult.
```

Architectural hypothesis:

```text
ExecutionOutcome should receive and preserve VerificationReport as structured
evidence. TaskVerificationResult remains a compatibility projection, not the
primary verifier boundary.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/verification/`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/OutcomeDominancePolicy.java`
- `src/main/java/dev/talos/runtime/outcome/`
- local trace and prompt-debug evidence packages

Why a one-off patch is insufficient:

```text
Every new verifier lane would otherwise have to encode structured claim facts
into legacy status/summary text, recreating the exact evidence-loss problem
T623 is trying to retire.
```

## Goal

```text
Thread VerificationReport from verifier execution through ExecutionOutcome,
outcome dominance, trace/debug evidence, and final-answer rendering without
letting compatibility TaskVerificationResult become the authoritative source.
```

## Non-Goals

- No browser, OCR, render, image, or PowerPoint verifier implementation.
- No LLM authority over verified claims.
- No broad outcome renderer rewrite.
- No removal of `TaskVerificationResult` compatibility in this ticket.

## Implementation Notes

- Introduce a result carrier that keeps both `VerificationReport` and
  `TaskVerificationResult`.
- Make `ExecutionOutcome` consume the rich report before mapping to
  `VerificationStatus`.
- Preserve existing final statuses for readback-only, failed, unavailable, and
  passed compatibility cases.
- Add trace/debug fields for required claim count, unsatisfied required claim
  count, strongest authoritative proof kinds, and limitations.
- Keep text rendering conservative: structured report can downgrade claims, but
  no model-authored or advisory evidence can raise a verdict.
- Carry verifier problems/limitations for unsatisfied required claims into
  outcome rendering, even when the compatibility status is `READBACK_ONLY`
  rather than `FAILED`.
- Fence embedded static verification parsing as advisory/negative-only evidence
  at the same boundary that consumes first-class reports.

## Architecture Metadata

Capability:

- Verification evidence and outcome truth.

Operation(s):

- verify

Owning package/class:

- `dev.talos.runtime.verification`
- `dev.talos.cli.modes.ExecutionOutcome`
- `dev.talos.cli.modes.OutcomeDominancePolicy`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: high outcome-truth risk if evidence is misprojected.
- Approval behavior: unchanged.
- Protected path behavior: unchanged; trace/debug additions must preserve redaction.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: required claim obligations must be represented explicitly.
- Verification profile: claim-scoped report, compatibility projection retained.
- Repair profile: unchanged.

Outcome and trace:

- Outcome/truth warnings: must reflect unsatisfied required obligations.
- Trace/debug fields: add structured claim/proof/authority evidence without raw
  sensitive content.

Refactor scope:

- Allowed: introduce a small carrier type and thread it through outcome creation.
- Forbidden: broad `ExecutionOutcome` rewrite or renderer churn unrelated to
  report propagation.

## Acceptance Criteria

- `ExecutionOutcome` can expose the rich `VerificationReport` for the current
  turn.
- Legacy `TaskVerificationResult` remains available for existing callers.
- `COMPLETED_VERIFIED` is still emitted only when required obligations are
  sufficiently satisfied by authoritative evidence.
- Readback-only README mutation behavior remains `COMPLETED_UNVERIFIED`.
- Embedded model-authored positive verification text remains non-authoritative
  and cannot produce `COMPLETED_VERIFIED`, including when
  `shouldVerifyPostApply(...)` is false.
- Embedded model-authored failure text may still lower/downgrade the outcome,
  but it must be labeled as embedded/advisory compatibility evidence rather than
  authoritative verifier proof.
- Unsatisfied required static-web interaction claims surface a concrete
  problem/limitation line in the final answer and trace/debug evidence while
  preserving the conservative `UNVERIFIED` verdict when runtime execution did
  not occur.
- Trace/debug output includes structured report summary without leaking
  protected content.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: rich report survives projection.
- Integration/executor test: `ExecutionOutcome` exposes report and still maps
  unsatisfied obligations to `COMPLETED_UNVERIFIED`.
- Integration/executor test: model-authored `[Static verification: passed - ...]`
  cannot produce `COMPLETED_VERIFIED` when post-apply verification is skipped.
- Integration/executor test: embedded static-verification failure remains a
  negative/downgrade path but is not authoritative positive evidence.
- Rendering test: unsatisfied required interaction report includes the specific
  static problem/limitation line rather than only generic readback wording.
- Trace assertion: required claim count and unsatisfied claim count recorded.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest" --no-daemon
.\gradlew.bat check --no-daemon
```

## Known Risks

- The report must not become a dumping ground for unredacted verifier details.
- Outcome rendering must not become dependent on fragile summary strings.

## Known Follow-Ups

- Browser/runtime behavior verifier lane.
- Document extraction verifier integration beyond status mapping.
