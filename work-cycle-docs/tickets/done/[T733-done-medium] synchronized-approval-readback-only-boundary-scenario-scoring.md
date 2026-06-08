# T733 - Synchronized Approval Readback-Only Boundary Scenario Scoring

Status: done
Priority: medium
Created: 2026-06-08

## Summary

The post-T731 synchronized approval rerun found that Qwen now clears the original T730 source-evidence failure in `t325-python-command-boundary`: it reads `problem.md`, writes both `dijkstra.py` and `test_dijkstra.py`, requests approval only for writes, and truthfully reports that Python/pytest was not run.

However, the generated `Scenario Result Scoring` table still reports this row as `FAIL_REVIEW_REQUIRED` because the trace is `PARTIAL` and verification status is `READBACK_ONLY`, not `PASSED`.

This is a reporting precision gap. The scenario-specific harness already accepts this boundary case when required files exist and the final answer does not overclaim Python execution. The generic summary table does not expose that distinction.

## Evidence

Audit root:

```text
local/manual-testing/current-0.10.0-post-t731-sync-20260608-170218/artifacts/qwen/sync-approval/t325-python-command-boundary
```

Key facts:

- `audit-transcript.json`
  - `traceStatus: PARTIAL`
  - `verificationStatus: READBACK_ONLY`
  - `verificationSummary: Target/readback checks passed for 2 mutated target(s); no task-specific static verifier was applicable.`
  - `approvalCount: 1`
- `traces/last-trace.txt`
  - two `SOURCE_EVIDENCE_WRITE_BEFORE_READ` blocks happened before approval;
  - model then read `problem.md`;
  - model wrote `test_dijkstra.py` and `dijkstra.py`;
  - `VERIFICATION_COMPLETED status=READBACK_ONLY`.
- final workspace contains nonblank `dijkstra.py` and `test_dijkstra.py`.
- final answer truthfully says:

```text
Python execution is outside the current bounded command profile.
No Python, pytest, or .py command result is available in this beta turn.
```

Relevant code path:

```text
src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditMain.java
```

## Why It Matters

The summary table currently conflates two different states:

- product failure requiring runtime fix;
- scenario accepted by the harness with a readback-only limitation because command execution is intentionally unavailable.

Release evidence should not call the synchronized lane clean while a row is `FAIL_REVIEW_REQUIRED`, but it also should not mislabel an accepted boundary scenario as an unresolved product failure.

## Acceptance Criteria

- The synchronized summary distinguishes `PARTIAL` + `READBACK_ONLY` boundary scenarios accepted by scenario-specific invariants from true failures.
- The label must remain explicit, for example `PASS_WITH_READBACK_ONLY_LIMITATION`, not plain `PASS`.
- The summary reason must say no task-specific verifier ran and the result is readback-only.
- Generic `PARTIAL` + `READBACK_ONLY` rows must remain review-required unless scenario-specific invariant evidence is available.
- The report must not claim Python/pytest or algorithm correctness was verified.

## Suggested Tests

- Add a summary-scoring test for the T325 transcript shape:
  - `traceStatus=PARTIAL`
  - `verificationStatus=READBACK_ONLY`
  - expected targets were created according to scenario-specific harness evidence
  - final answer truthfully reports Python/pytest unavailable
  - expected score is a readback-only limitation score, not `FAIL_REVIEW_REQUIRED` and not plain `PASS`.
- Add a negative test where a generic partial/readback-only scenario remains `FAIL_REVIEW_REQUIRED`.

## Current Evidence Status

Done on 2026-06-08.

Implementation:

- `SynchronizedApprovalAuditMain` now has an explicit `PASS_WITH_READBACK_ONLY_LIMITATION` score.
- The score is evidence-gated for the `t325-python-command-boundary` scenario: the expected Python files must exist and be nonblank, and the final answer must report that Python/pytest execution was unavailable without claiming tests passed or algorithm correctness was verified.
- Generic `PARTIAL` + `READBACK_ONLY` transcripts remain `FAIL_REVIEW_REQUIRED`.

Regression evidence:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_can_run_single_t325_scenario" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
```

The deterministic summary now labels the accepted boundary as `PASS_WITH_READBACK_ONLY_LIMITATION` instead of plain `PASS` or `FAIL_REVIEW_REQUIRED`.

Post-fix synchronized approval evidence:

```text
local/manual-testing/current-0.10.0-post-t733-sync-20260608-174534
```

Both Qwen and GPT-OSS `t325-python-command-boundary` rows are scored as `PASS_WITH_READBACK_ONLY_LIMITATION`. No `FAIL_REVIEW_REQUIRED` rows were found in either synchronized summary, and the artifact canary scan passed for the audit root and workspaces.
