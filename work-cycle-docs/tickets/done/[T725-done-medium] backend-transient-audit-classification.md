# [T725-done-medium] Backend Transient Audit Classification

Status: done
Priority: medium

## Evidence Summary

- Source: `current-0.10.0-full-two-model-20260608-082529` full two-model audit
- Date: 2026-06-08
- Talos version / commit: `0.10.0` / `6c05f8f0b34110faa80a04630a98cd9a2544510e`
- Model/backend: Qwen managed backend
- Related finding: `F-0.10-AUDIT-001`

Expected behavior:

```text
Release-gate reports should distinguish backend transport contamination from
product/runtime failures. A backend HTTP 0/no-tool failure should not be scored
as an ordinary product fail without labeling and bounded rerun evidence.
```

Observed behavior:

```text
Two Qwen redirected safe cases failed with backend HTTP 0 before tool calls.
A focused rerun passed both. The packet needs explicit contamination
classification and rerun reporting.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `TRACE_REDACTION`

Blocker level:

- candidate follow-up

Why this level:

```text
This is audit-evidence hygiene for release gates. It does not prove a product
bug, but without classification the report can misattribute backend transport
failures to Talos runtime behavior.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The TalosBench manual-eval runner already distinguishes lane types and approval
drift, but it does not classify no-tool backend HTTP 0 failures as contaminated
release evidence or preserve a bounded rerun result in the summary.
```

Likely code/document areas:

- `tools/manual-eval/run-talosbench.ps1`

## Goal

```text
Classify backend HTTP 0/no-tool failures as BACKEND_CONTAMINATED and support one
bounded strict-lane rerun that preserves original and rerun artifacts.
```

## Non-Goals

- No backend/server retry policy change inside Talos runtime.
- No model profile change.
- No broad audit-runner rewrite.

## Implementation Notes

Detect:

- engine/backend response error;
- HTTP 0 shape;
- zero tool calls;
- failed trace/outcome before product tool evidence.

Strict release lanes rerun once. If rerun passes, report
`CONTAMINATED_THEN_RERUN_PASS`. If rerun fails with runtime/tool evidence, report
the real product failure.

## Architecture Metadata

Capability:

- Audit runner classification.

Operation(s):

- Manual-eval audit execution and summary classification.

Owning package/class:

- `tools/manual-eval/run-talosbench.ps1`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium evidence-quality risk.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: preserve original and rerun artifacts.
- Verification profile: TalosBench audit scoring.
- Repair profile: none.

Outcome and trace:

- Outcome/truth warnings: reports must not hide contaminated original runs.
- Trace/debug fields: audit summary fields only.

Refactor scope:

- Narrow result-classification and rerun helpers.

## Acceptance Criteria

- A self-test fixture with HTTP 0, backend response error, zero tools, and failed trace is classified as `BACKEND_CONTAMINATED`.
- Strict release lanes rerun backend-contaminated cases once and preserve original artifacts.
- Passing rerun reports `CONTAMINATED_THEN_RERUN_PASS`.
- Product failures with runtime/tool evidence remain ordinary failures.
- No regressions to approval-drift classification.

## Tests / Evidence

Implemented:

- `tools/manual-eval/run-talosbench.ps1` now classifies HTTP 0 /
  `BACKEND_RESPONSE_ERROR` no-tool failures as `BACKEND_CONTAMINATED`.
- Strict evidence runs rerun backend-contaminated cases once and preserve the
  original artifact directory as `*-backend-contaminated-original`.
- Passing reruns are reported as `CONTAMINATED_THEN_RERUN_PASS`.

Verification evidence:

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest` passed.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` passed.
- `.\gradlew.bat check --no-daemon` passed.

Required deterministic regression:

- TalosBench self-test fixture for backend contamination.
- TalosBench self-test fixture for product failure not misclassified.
- TalosBench self-test fixture for rerun result reporting where practical.

Commands:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

## Work-Test Cycle Notes

- Use inner dev loop.
- Do not run a full live audit as part of this ticket.

## Known Risks

- Full rerun behavior may need to be validated by focused manual audit after deterministic tests.

## Known Follow-Ups

- T280/T284/T306/T312 full release-gate rerun.
