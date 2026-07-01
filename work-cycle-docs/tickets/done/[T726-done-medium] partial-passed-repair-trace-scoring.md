# [T726-done-medium] Partial/PASSED Repair Trace Scoring

Status: done
Priority: medium

## Evidence Summary

- Source: `current-0.10.0-full-two-model-20260608-082529` synchronized approval audit
- Date: 2026-06-08
- Talos version / commit: `0.10.0` / `6c05f8f0b34110faa80a04630a98cd9a2544510e`
- Model/backend: GPT-OSS synchronized approval lane
- Related finding: `F-0.10-AUDIT-004`

Expected behavior:

```text
Audit reports should distinguish a true failure from a runtime-repaired turn:
traceStatus=PARTIAL with verificationStatus=PASSED can be acceptable only when
partial status came from blocked invalid intermediate calls and final workspace
state is verified.
```

Observed behavior:

```text
GPT-OSS had synchronized scenarios with traceStatus=PARTIAL and
verificationStatus=PASSED. The packet did not clearly score these as
PASS_WITH_RUNTIME_REPAIR versus failure.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `REPAIR_CONTROL`
- `VERIFICATION`

Blocker level:

- candidate follow-up

Why this level:

```text
This is release-report truthfulness. The runtime behavior may be good, bad, or
mixed depending on the details, but the audit packet must not overclaim a fully
clean run or misclassify successful guarded repair as a product failure.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The synchronized approval reporting has insufficient scoring vocabulary for
partial trace plus passed verification. It needs a visible class for
PASS_WITH_RUNTIME_REPAIR and a stricter failure path when partial status
includes failed mutation, wrong target, missing verifier, or false answer.
```

Likely code/document areas:

- synchronized approval audit runner and summary/report code
- `tools/manual-eval/run-talosbench.ps1` if the scoring is shared there
- `src/test/java/dev/talos/harness/*SynchronizedApproval*`

## Goal

```text
Make partial/passed repair rows explicit in synchronized audit summaries and
prevent release reports from calling them fully clean.
```

## Non-Goals

- No change to runtime repair policy unless review finds a true runtime bug.
- No broad verifier rewrite.
- No live audit in this implementation ticket.

## Implementation Notes

Review the GPT-OSS partial/passed rows. If the evidence shows only invalid
intermediate calls blocked by runtime followed by verified final state, classify
as `PASS_WITH_RUNTIME_REPAIR`. If evidence shows failed mutation, wrong file,
missing verifier, or false final answer, keep as failure and create a narrower
runtime ticket.

## Architecture Metadata

Capability:

- Synchronized audit scoring.

Operation(s):

- Audit result classification.

Owning package/class:

- synchronized approval audit runner/reporting code.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium release-evidence risk.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: preserve trace/verifier/final-state distinction.
- Verification profile: audit scoring.
- Repair profile: runtime repair scoring.

Outcome and trace:

- Outcome/truth warnings: partial/passed rows must be visible as runtime repair, not clean pass.
- Trace/debug fields: audit report fields only.

Refactor scope:

- Narrow scoring/reporting helper only.

## Acceptance Criteria

- A synchronized audit summary can classify `traceStatus=PARTIAL` + `verificationStatus=PASSED` + only blocked invalid intermediate calls as `PASS_WITH_RUNTIME_REPAIR`.
- A partial trace with failed verifier remains failure.
- A partial trace with wrong file, failed mutation, missing verifier, or false final answer remains failure or gets a narrower runtime ticket.
- Reports surface partial/passed rows explicitly.
- No regressions to synchronized approval evidence requirements.

## Tests / Evidence

Implemented:

- `SynchronizedApprovalAuditMain` now writes a `Scenario Result Scoring`
  section in `SYNCHRONIZED-APPROVAL-AUDIT.md`.
- `PARTIAL` + `PASSED` + `TOOL_CALL_BLOCKED` is scored as
  `PASS_WITH_RUNTIME_REPAIR`.
- Partial traces without passed verification or without blocked-call repair
  evidence remain `FAIL_REVIEW_REQUIRED`.

Verification evidence:

- `.\gradlew.bat e2eTest --tests "dev.talos.harness.*SynchronizedApproval*" --no-daemon` passed.
- `.\gradlew.bat check --no-daemon` passed.

Required deterministic regression:

- Synchronized approval summary test for `PASS_WITH_RUNTIME_REPAIR`.
- Synchronized approval summary test for partial/failed-verifier failure.

Commands:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.*SynchronizedApproval*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use inner dev loop.
- Do not close broader release-gate tickets from this ticket alone.

## Known Risks

- The current partial/passed rows may require manual evidence review before deterministic scoring can be precise.

## Known Follow-Ups

- T280/T284/T306/T312 full release-gate rerun.
