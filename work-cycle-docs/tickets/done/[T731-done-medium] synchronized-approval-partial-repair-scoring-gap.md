# T731 - Synchronized Approval Partial Repair Scoring Gap

Status: done
Priority: medium
Created: 2026-06-08

## Summary

The 0.10.0 post-T729 synchronized approval GPT-OSS live audit passed the Gradle task and artifact scan, but its generated `Scenario Result Scoring` table included `FAIL_REVIEW_REQUIRED` for `mutation-append-line-verified`.

The underlying runtime behavior appears safe and verified:

- GPT-OSS read `README.md`.
- GPT-OSS attempted an invalid `write_file` that did not preserve the same-turn readback.
- Runtime blocked that invalid write before approval with `APPEND_LINE_WRITE_BEFORE_VALID_PRESERVATION`.
- GPT-OSS retried with a valid write.
- One approval was requested and granted for the valid `write_file`.
- Append-line verification passed.
- Trace status was `PARTIAL`; verification status was `PASSED`.

This is the exact class T726 intended to distinguish from real failures, but the current scenario scoring did not classify it as `PASS_WITH_RUNTIME_REPAIR`.

## Evidence

Audit root:

```text
local/manual-testing/current-0.10.0-post-t729-sync-r2-20260608-164342/artifacts/gptoss/sync-approval/mutation-append-line-verified
```

Key artifacts:

```text
audit-transcript.json
traces/last-trace.txt
model-transcript.txt
final-answer.txt
```

`audit-transcript.json`:

```text
approvalCount: 1
traceStatus: PARTIAL
verificationStatus: PASSED
verificationSummary: Append line verification passed.
toolEventTypes include:
  ACTION_OBLIGATION_EVALUATED
  PENDING_ACTION_OBLIGATION_RAISED
  APPROVAL_REQUIRED
  APPROVAL_GRANTED
  EXPECTATION_VERIFIED
```

`traces/last-trace.txt` records:

```text
ACTION_OBLIGATION_EVALUATED {
  status=FAILED,
  failureKind=APPEND_LINE_WRITE_BEFORE_VALID_PRESERVATION,
  obligation=APPEND_LINE_WRITE_PRESERVATION,
  reason=append-line write_file for README.md does not preserve the complete same-turn readback and append exactly `Release gate note`.
}
...
EXPECTATION_VERIFIED {
  kind=APPEND_LINE,
  status=PASSED
}
VERIFICATION_COMPLETED {
  status=PASSED
}
```

Generated summary:

```text
mutation-append-line-verified | PARTIAL | PASSED | FAIL_REVIEW_REQUIRED | partial trace did not have both passed verification and blocked-call repair evidence
```

## Why It Matters

Release-gate reporting currently has two conflicting signals:

- Gradle task exit: success.
- Scenario scoring: `FAIL_REVIEW_REQUIRED`.

That ambiguity makes it too easy to overclaim a synchronized approval lane as clean, while also making a safe runtime repair look like an unresolved product failure.

## Acceptance Criteria

- `SynchronizedApprovalAuditMain.evaluateTranscriptForSummary(...)` or equivalent scoring logic recognizes pre-approval invalid-call repair evidence for append-line preservation failures.
- A scenario with `traceStatus=PARTIAL`, `verificationStatus=PASSED`, `APPEND_LINE_WRITE_BEFORE_VALID_PRESERVATION`, and final `EXPECTATION_VERIFIED` append-line pass is scored as `PASS_WITH_RUNTIME_REPAIR`.
- A scenario with `traceStatus=PARTIAL` and failed/missing verification remains `FAIL_REVIEW_REQUIRED`.
- A scenario with a failed approved mutation, wrong file, or false final answer remains `FAIL_REVIEW_REQUIRED`.
- The summary remains explicit that `PASS_WITH_RUNTIME_REPAIR` is not a perfectly clean turn.
- Decide whether `FAIL_REVIEW_REQUIRED` rows should make `runSynchronizedApprovalAudit` exit non-zero for release lanes. If not, the summary must state that scenario-score failures require human review even when the process exits zero.

## Suggested Tests

- Extend `SynchronizedApprovalAuditRunnerTest` with the current GPT-OSS transcript shape:
  - `PARTIAL`
  - `PASSED`
  - `APPEND_LINE_WRITE_BEFORE_VALID_PRESERVATION`
  - `EXPECTATION_VERIFIED kind=APPEND_LINE status=PASSED`
  - expected score `PASS_WITH_RUNTIME_REPAIR`
- Keep existing tests where partial verification failure remains `FAIL_REVIEW_REQUIRED`.

## Current Evidence Status

Post-T729 evidence:

- GPT-OSS synchronized approval live lane produced 25 scenario bundles and artifact scan PASS.
- The generated scenario scoring table still contains one `FAIL_REVIEW_REQUIRED` row.
- Qwen synchronized approval live lane failed separately at `t325-python-command-boundary`, tracked by T730.

## Implementation Evidence

Implemented in:

```text
src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditMain.java
```

Behavior:

- `PARTIAL` + `PASSED` transcript rows now score as `PASS_WITH_RUNTIME_REPAIR` when structured runtime-repair evidence is present;
- blocked-call repair evidence still counts;
- append-line preservation repair evidence now counts when the transcript includes `PENDING_ACTION_OBLIGATION_RAISED`, `APPROVAL_REQUIRED`, `APPROVAL_GRANTED`, and `EXPECTATION_VERIFIED`;
- partial rows with failed/missing verification or no repair evidence remain `FAIL_REVIEW_REQUIRED`.

Regression tests:

```text
SynchronizedApprovalAuditRunnerTest.synchronized_summary_scores_appendLineObligationRepairAsRuntimeRepairPass
SynchronizedApprovalAuditRunnerTest.synchronized_summary_scores_partial_passed_blocked_call_as_runtime_repair_pass
SynchronizedApprovalAuditRunnerTest.synchronized_summary_keeps_partial_failed_verifier_as_review_required
SynchronizedApprovalAuditRunnerTest.synchronized_summary_keeps_partial_without_blocked_repair_evidence_as_review_required
```

Verification run:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
```

Live synchronized approval lane must still be rerun before this contributes to a beta-readiness claim.
