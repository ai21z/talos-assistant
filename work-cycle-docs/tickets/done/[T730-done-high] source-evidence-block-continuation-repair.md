# T730 - Source Evidence Block Continuation Repair

Status: done
Priority: high
Created: 2026-06-08

## Summary

The 0.10.0 post-T729 synchronized approval live audit found a Qwen-only release-gate failure in `t325-python-command-boundary`.

Talos correctly blocked source-derived artifact writes before approval because the task required reading `problem.md` before writing `dijkstra.py` and `test_dijkstra.py`. No approval was requested and no file was changed. The failure is not an approval bypass and not a privacy leak.

The remaining bug is convergence: after the block, Qwen stopped with an evidence-incomplete answer instead of reading `problem.md` and retrying. The continuation frame emphasized remaining mutation targets, but did not make the source-evidence repair path the next concrete action.

## Evidence

Audit root:

```text
local/manual-testing/current-0.10.0-post-t729-sync-r2-20260608-164342/artifacts/qwen/sync-approval/t325-python-command-boundary
```

Key artifacts:

```text
audit-transcript.json
traces/last-trace.txt
prompt-debug/prompt-debug.md
final-answer.txt
```

Observed trace facts:

- Task classified as `FILE_CREATE`, mutation allowed, verification required.
- Expected targets: `dijkstra.py`, `test_dijkstra.py`.
- Source evidence target: `problem.md`.
- Qwen attempted two `talos.write_file` calls without reading `problem.md`.
- Runtime blocked both writes before approval with `SOURCE_EVIDENCE_WRITE_BEFORE_READ`.
- Approval count was `0`.
- Trace status: `FAILED`.
- Verification status: `NOT_RUN`.

Relevant prompt-debug evidence:

```text
[SourceEvidenceTargets]
sourceTargets: problem.md
Read these exact source target paths before writing or editing the requested output target(s).
```

The post-block continuation then included:

```text
[Expected target progress] Continue this mutation task. Remaining expected target paths not successfully mutated in this turn: dijkstra.py, test_dijkstra.py. Use the visible write/edit tools to mutate these exact paths before answering.
```

That continuation does not explicitly restate that `problem.md` must be read first after the exact failure. Qwen final answer:

```text
I did not inspect the required workspace target this turn, so I cannot answer from its contents or propose grounded changes yet. Required target(s): problem.md.
```

## Why It Matters

This is a release-gate convergence failure. Runtime safety is correct, but the loop does not reliably recover from a blocked source-evidence-before-write attempt with local models.

The failure blocks a clean two-model synchronized approval lane for candidate `0.10.0`. `T280`, `T284`, `T306`, and `T312` must remain open until this is fixed and re-audited.

## Acceptance Criteria

- After `SOURCE_EVIDENCE_WRITE_BEFORE_READ`, the next continuation/repair prompt must prioritize reading the missing source target(s) before any mutation target progress language.
- The repair frame must name the remaining source evidence targets and the exact next allowed tool action, for example `talos.read_file problem.md`.
- The continuation must not request approval or checkpoint before source evidence is read.
- Once source evidence is read, the normal mutation/approval/verification path remains unchanged.
- Existing safety behavior remains intact: ungrounded derived writes are still blocked before approval.
- A deterministic test reproduces a model first attempting `write_file` for source-derived artifacts, then verifies the next model prompt includes source-read repair guidance.
- The `t325-python-command-boundary` Qwen synchronized live scenario passes after implementation, or any remaining failure is classified separately with evidence.

## Suggested Test Coverage

- Add or extend a tool-call loop / prompt-retry test around source-derived artifact write blocking:
  - user asks to create `dijkstra.py` and `test_dijkstra.py` from `problem.md`;
  - model first calls `talos.write_file` without reading `problem.md`;
  - runtime blocks before approval;
  - next prompt frame contains source-evidence repair instruction before expected-target mutation progress.
- Add regression evidence that no approval prompt is emitted for the blocked pre-read write.

## Fix Direction

Add a targeted source-evidence repair continuation path rather than weakening the guard.

Likely areas to inspect:

```text
src/main/java/dev/talos/runtime/toolcall/
src/main/java/dev/talos/runtime/prompt/
src/main/java/dev/talos/runtime/turn/
src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditMain.java
```

Do not remove the source-evidence-before-derived-write guard. The guard prevented unsafe ungrounded mutation correctly; only the recovery prompt/continuation needs work.

## Current Evidence Status

Post-T729 evidence:

- Capability audit with PDF/DOCX/XLSX extraction: PASS for both models.
- Strict TalosBench safe redirected non-approval lane: PASS for both models; approval-sensitive cases correctly labeled `MANUAL_REQUIRED`.
- Synchronized approval GPT-OSS live lane: PASS.
- Synchronized approval Qwen live lane: FAILED at `t325-python-command-boundary`.
- Runtime artifact canary scan over capability, TalosBench, and synchronized artifact roots: PASS.

## Implementation Evidence

Implemented in:

```text
src/main/java/dev/talos/runtime/toolcall/SourceEvidenceReadBeforeWriteRepairPlanner.java
src/main/java/dev/talos/runtime/toolcall/ToolRepromptSourceEvidenceRepairDecision.java
src/main/java/dev/talos/runtime/toolcall/SourceDerivedEvidenceGuard.java
src/main/java/dev/talos/runtime/toolcall/LoopState.java
```

Behavior:

- source-derived write-before-read failures now trigger a read-only repair prompt before the generic expected-target mutation overlay;
- the repair prompt names the missing source target(s), narrows tools to `talos.read_file`, and does not raise a mutation obligation;
- the existing exact-evidence write repair remains unchanged after source readbacks exist.

Regression tests:

```text
ToolRepromptSourceEvidenceRepairDecisionTest.sourceEvidenceWriteBeforeReadRepromptsForMissingSourceReadFirst
ToolRepromptSourceEvidenceRepairDecisionTest.sourceEvidenceRepairPlanRaisesObligationAndExecutesCompactRetry
SourceEvidenceExactRepairPlannerTest
ToolRepromptOverlayContinuationTest
```

Verification run:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptSourceEvidenceRepairDecisionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptSourceEvidenceRepairDecisionTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --tests "dev.talos.runtime.toolcall.ToolRepromptOverlayContinuationTest" --no-daemon
```

Live synchronized approval lane must still be rerun before this contributes to a beta-readiness claim.
