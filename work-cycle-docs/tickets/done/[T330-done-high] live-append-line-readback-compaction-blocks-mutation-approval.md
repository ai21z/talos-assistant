# T330 - Live Append-Line Readback Compaction Blocks Mutation Approval

Status: done
Severity: high
Release gate: no for T295; yes for full synchronized live-audit pass
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-20
Owner: unassigned

## Problem

The GPT-OSS live synchronized approval rerun for T295 completed the private-document evidence scenarios, then failed later at `mutation-append-line-verified`.

The failure was not an approval leak and not a privacy failure. It was a live mutation-convergence failure:

- task contract correctly classified the prompt as `FILE_EDIT`
- mutating tools were visible
- the model read `README.md`
- the read result was compacted in model context
- the model attempted `talos.write_file` with incomplete content
- runtime rejected the invalid append-line full-write before approval
- the model then repeated read-only calls
- no valid mutation reached the approval gate
- the synchronized live bank failed because the expected single write approval was never observed

## Evidence

Live command:

```text
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditMode=live" "-PapprovalAuditConfig=$env:USERPROFILE\.talos\config.yaml" "-PapprovalAuditArtifactsRoot=local/manual-testing/synchronized-approval-live-gptoss-t295-20260520-r2" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/synchronized-approval-live-gptoss-t295-20260520-r2" --no-daemon
```

Failure summary:

```text
local/manual-testing/synchronized-approval-live-gptoss-t295-20260520-r2/SYNCHRONIZED-APPROVAL-AUDIT-FAILED.md
Completed scenarios before failure: 18
Failure message: mutation-append-line-verified (Expected 1 approval prompt(s), observed 0.)
```

Scenario evidence:

```text
local/manual-testing/synchronized-approval-live-gptoss-t295-20260520-r2/mutation-append-line-verified/audit-transcript.json
local/manual-testing/synchronized-approval-live-gptoss-t295-20260520-r2/mutation-append-line-verified/model-transcript.txt
local/manual-testing/synchronized-approval-live-gptoss-t295-20260520-r2/mutation-append-line-verified/traces/last-trace.txt
local/manual-testing/synchronized-approval-live-gptoss-t295-20260520-r2/mutation-append-line-verified/prompt-debug/prompt-debug.md
```

Trace excerpt:

```text
TASK_CONTRACT_RESOLVED {taskType=FILE_EDIT, mutationAllowed=true, verificationRequired=true}
ACTION_OBLIGATION_EVALUATED {obligation=MUTATING_TOOL_REQUIRED, status=SELECTED}
TOOL_CALL_PARSED talos.read_file {pathHint=README.md}
TOOL_EXECUTED talos.read_file {success=true}
ACTION_OBLIGATION_EVALUATED {obligation=APPEND_LINE_WRITE_PRESERVATION, status=FAILED}
```

Model transcript excerpt:

```text
tool result: [compacted: talos.read_file result, 57 chars - full output elided to keep context focused]
assistant tool call: talos.write_file path=README.md content="# Demo\n\nRelease gate note"
tool error: append-line write_file for README.md does not preserve the complete same-turn readback and append exactly `Release gate note`.
```

Final answer was honest:

```text
[Truth check: no file was changed in this turn because the requested write tool call was invalid.]
```

Workspace diff:

```text
(no file changes detected)
```

## Classification

Category: mixed runtime/model failure

Runtime-owned:

- The runtime correctly rejected the unsafe/incomplete full-write before approval.
- The runtime correctly avoided false success.
- The runtime did not leak mutation through approval.

Model-authored:

- The model failed to repair the append-line mutation after the preapproval rejection.

Harness/runtime-prompt owned:

- Same-turn readback compaction may be counterproductive for exact append/write repair prompts.
- The repair instruction says to mutate the target, but does not give a robust append-specific recovery path after `APPEND_LINE_WRITE_PRESERVATION` failure.

## Why it matters

This blocks full synchronized live-audit completion even though the T295 private-document scenarios completed. It also mirrors real user frustration: Talos has enough state to know an append was requested, but the live model can get stuck after the verifier rejects an incomplete full-file write.

## Recommended fix direction

Prefer one of these:

1. Add a narrow native append-line/edit operation or `edit_file` repair path for append-only tasks so the model does not have to reconstruct a whole file from compacted readback.
2. For same-turn append verification failures, inject a repair frame containing the exact required old/new target shape and explicitly recommend `talos.edit_file` when possible.
3. Avoid compacting the same-turn readback when the current task is an exact append/full-write preservation task and the file is below the normal small-file threshold.

Do not weaken the preapproval preservation check. The rejection before approval is the part that worked.

## Implementation update - 2026-05-20

Implemented a deterministic compact repair path for append-line preapproval failures:

- `ToolCallLoop.ToolOutcome.appendLinePreservationFailure()` now classifies the specific preapproval rejection produced by `APPEND_LINE_WRITE_PRESERVATION`.
- `ToolCallRepromptStage` now detects a failed append-line full-write for a remaining expected mutation target, retrieves the complete same-turn readback from runtime state, and sends a compact `[AppendLineRepair]` frame instead of the oversized full-history continuation.
- The compact repair frame includes only:
  - current user request
  - exact target path
  - exact required appended line
  - latest successful same-turn readback
  - write/edit-only tool surface
- `PendingActionObligation` now has `APPEND_LINE_TARGET_REPAIR`, so if the model responds to the compact repair with prose or the wrong target/tool, Talos stops deterministically instead of drifting back into read-only loops.
- Sensitive readback paths are not injected into the compact append-line repair frame.

Focused test evidence:

```text
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.appendLinePreapprovalFailureUsesCompactRepairWithReadbackBeforeApproval" --no-daemon
BUILD SUCCESSFUL

.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
BUILD SUCCESSFUL

.\gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
BUILD SUCCESSFUL
```

Close criteria:

- Fresh true-live synchronized approval bank must show `mutation-append-line-verified` reaches exactly one approval prompt and writes `Release gate note`.
- If the live bank fails later, create a new ticket for the next blocker rather than reopening this root cause unless the append-line scenario regresses.

## Live evidence update - 2026-05-20

Fresh GPT-OSS live synchronized approval bank:

```text
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditMode=live" "-PapprovalAuditConfig=$env:USERPROFILE\.talos\config.yaml" "-PapprovalAuditArtifactsRoot=local/manual-testing/synchronized-approval-live-gptoss-t330-20260520-r1" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/synchronized-approval-live-gptoss-t330-20260520-r1" --no-daemon
```

The broader bank failed later at `static-web-selector-script-only-verified`, but `mutation-append-line-verified` passed before that failure.

Append-line bundle:

```text
local/manual-testing/synchronized-approval-live-gptoss-t330-20260520-r1/mutation-append-line-verified/AUDIT-BUNDLE.md
```

Evidence:

```text
Approvals observed: 1
TOOL_CALL_PARSED talos.read_file {pathHint=README.md}
TOOL_EXECUTED talos.read_file {pathHint=README.md, success=true}
TOOL_CALL_PARSED talos.write_file {pathHint=README.md}
PERMISSION_DECISION talos.write_file {action=ASK, pathHint=README.md}
APPROVAL_GRANTED talos.write_file {pathHint=README.md}
TOOL_EXECUTED talos.write_file {pathHint=README.md, success=true}
EXPECTATION_VERIFIED {status=PASSED, kind=APPEND_LINE, pathHint=README.md}
OUTCOME_RENDERED {status=COMPLETE, classification=COMPLETED_VERIFIED}
```

This closes T330. The later full-live-bank failure is tracked separately as T331.

## Regression test

Add a live-harness or deterministic model-script scenario that reproduces:

```text
Read README.md, then append exactly this line to README.md: Release gate note
```

Expected:

- if the model first attempts an incomplete full-write, runtime repair gives a valid path to mutate
- exactly one mutation approval is eventually requested
- final workspace contains the original file plus the appended line
- verification passes

## Release gate impact

- Not a T295 privacy blocker.
- Blocks claiming the full synchronized live approval bank passes at the current head.
- Related to T311, but this ticket captures the current live GPT-OSS evidence after private-document approval work.
