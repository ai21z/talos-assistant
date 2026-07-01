# T721 - Exact-Target Multifile Creation Tool Surface Batch Drift

Status: done
Priority: high
Created: 2026-06-08
Source audit: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026`
Completed: 2026-06-08

## Problem

In the Qwen `0.10.0` synchronized approval audit, scenario
`t325-python-command-boundary` failed before approval. The model was asked to
create `dijkstra.py` and `test_dijkstra.py` from `problem.md`, then run pytest
if available. Instead of reading `problem.md` and writing the exact target
files, it called `talos.apply_workspace_batch` with unsupported `write_file`
operations. Runtime correctly blocked the invalid batch before approval and no
file changed, but the scenario never reached the intended synchronized approval
or command boundary.

Evidence:

- Audit report: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/FINDINGS.md`
- Failure bundle: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/artifacts/qwen/sync-approval/t325-python-command-boundary/AUDIT-BUNDLE.md`
- Trace: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/artifacts/qwen/sync-approval/t325-python-command-boundary/traces/last-trace.txt`
- Final answer: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/artifacts/qwen/sync-approval/t325-python-command-boundary/final-answer.txt`

Key trace facts:

- `TASK_CONTRACT_RESOLVED`: `FILE_CREATE`, `mutationAllowed=true`,
  `verificationRequired=true`.
- `ExpectedTargets`: `dijkstra.py`, `test_dijkstra.py`.
- `SourceEvidenceTargets`: `problem.md`.
- `TOOL_CALL_PARSED talos.apply_workspace_batch`.
- `TOOL_CALL_BLOCKED`: unsupported batch operation `write_file`; no approval was
  requested and no file changed.

## Why It Matters

This is not an unsafe mutation defect: the pre-approval guard worked. It is a
release-gate convergence and tool-surface defect. For exact target multifile
creation with a named source target, exposing broad workspace batch tools gives
local models a path to choose an unsupported schema and burn the turn before the
approval/verification path can run.

## Acceptance Criteria

- Exact-target multifile creation with named source evidence strongly prefers
  concrete file tools (`talos.read_file`, `talos.write_file`) over broad batch
  tools unless the batch schema actually supports the requested operation.
- `apply_workspace_batch` is not exposed for this exact shape, or it is rejected
  with a bounded target-specific retry before the turn fails.
- A model attempt to use unsupported batch operations does not consume the whole
  turn without a deterministic repair/failure path that names the exact valid
  targets and source evidence.
- The `t325-python-command-boundary` synchronized approval scenario reaches the
  intended approval/command boundary or fails for a command-policy reason, not
  unsupported batch operation drift.

## Regression Test

Add focused coverage around the current turn/tool-surface or synchronized
approval harness:

- Given a `FILE_CREATE` contract with required targets `dijkstra.py` and
  `test_dijkstra.py` and source target `problem.md`, selected tools should not
  include `apply_workspace_batch` unless that tool can validly write files.
- If a backend emits `apply_workspace_batch` with unsupported `write_file`
  operations, runtime should produce a target-specific retry/failure that does
  not masquerade as an approval failure.

## Implementation Evidence

- `ToolSurfacePlanner` now selects a `source-derived file creation apply surface`
  for `FILE_CREATE` contracts with concrete expected file targets and source
  evidence targets. The visible surface is read/search plus `talos.write_file`;
  workspace batch/organization tools are not exposed for this shape.
- Regression: `ToolSurfacePlannerTest.sourceDerivedExactFileCreationUsesFileWriteSurface`.
- Verification:
  - `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.toolcall.StaticWebRepairPathGuardTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon`
  - `.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon`

