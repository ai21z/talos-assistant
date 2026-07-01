# T332 - Static Web Selector Fix Must Not Expose Rename Path

Status: done - narrow file-edit/static selector tasks no longer expose or accept workspace-organization tools without explicit workspace-operation intent
Severity: high
Release gate: yes for broad static-web beta claims
Branch: v0.9.0-beta-dev
Created: 2026-05-20
Closed: 2026-05-20

## Problem

The live synchronized audit for `static-web-selector-script-only-verified` failed before the T325 scenario because GPT-OSS used `talos.rename_path` instead of editing `script.js`.

The runtime did not claim success: static verification failed and the final answer was replaced with an honest failure report. That is good. The remaining problem is still serious: a narrow selector-fix task should not expose or accept workspace-organization tools such as `talos.rename_path` when the expected action is to edit the named source file.

## Evidence

Failed live run:

```powershell
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditMode=live" "-PapprovalAuditConfig=$env:USERPROFILE\.talos\config.yaml" "-PapprovalAuditArtifactsRoot=local/manual-testing/synchronized-approval-live-gptoss-t325-20260520-r1" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/synchronized-approval-live-gptoss-t325-20260520-r1" --no-daemon
```

Failure summary:

```text
local/manual-testing/synchronized-approval-live-gptoss-t325-20260520-r1/SYNCHRONIZED-APPROVAL-AUDIT-FAILED.md
```

Scenario bundle:

```text
local/manual-testing/synchronized-approval-live-gptoss-t325-20260520-r1/static-web-selector-script-only-verified/
```

Observed trace transcript:

```json
"approvalDescriptions" : [ "write operation: talos.rename_path" ],
"traceStatus" : "FAILED",
"verificationStatus" : "FAILED",
"verificationSummary" : "Replacement verification failed."
```

Observed final answer:

```text
[Used 2 tool(s): talos.read_file, talos.rename_path | 2 iteration(s)]

[Task incomplete: Static verification failed - Replacement verification failed.]
...
Applied mutating tool calls:
- script-old.js: Renamed script.js -> script-old.js
```

Final workspace evidence:

```text
script.js is missing because it was renamed to script-old.js.
index.html still references script.js.
```

## Expected Behavior

For a prompt such as:

```text
Read script.js, then fix the selector bug by changing .missing-button to .cta-button.
Do not edit scripts.js.
```

Talos should expose and accept only the file-edit/write path needed for the expected target:

```text
talos.read_file
talos.edit_file
talos.write_file
```

It should not expose or accept:

```text
talos.rename_path
talos.move_path
talos.copy_path
talos.delete_path
talos.apply_workspace_batch
```

unless the user explicitly asks for workspace organization or batch operations.

## Impact

This is not a false-success bug because verification caught the bad outcome. It is still a high beta blocker because the approval prompt can ask the user to approve an irrelevant mutation that damages the workspace before verification catches it.

## Resolution

Implemented:

1. `ToolSurfacePlanner` now narrows `FILE_EDIT` tasks with concrete file targets to the file-edit surface unless the task has explicit workspace-operation intent.
2. `TurnProcessor` now rejects workspace-organization tools before approval for narrow file-edit tasks when no workspace-operation intent exists.
3. `WorkspaceOperationIntent` now preserves explicit `talos.apply_workspace_batch` contracts after `TaskContractResolver` has classified them as `explicit-batch-workspace-apply-request`, so the T332 guard does not break real batch-operation scenarios.
4. The synchronized approval audit runner can selectively replay `static-web-selector-script-only-verified` in scripted and live modes.

The backstop static verifier remains in place and still reports failed static-web coherence honestly.

## Regression Tests

Added focused tests:

```text
ToolSurfacePlannerTest.staticSelectorRepairDoesNotExposeWorkspaceOrganizationTools
ToolCallLoopTest.staticSelectorRepairRenamePathIsBlockedBeforeApproval
ToolSurfacePlannerTest.explicitBatchWorkspaceCopyPromptKeepsBatchSurfaceForFileTargets
SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_can_run_single_static_web_selector_scenario
```

## Verification

Focused deterministic tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.ToolCallLoopTest.staticSelectorRepairRenamePathIsBlockedBeforeApproval" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_can_run_single_static_web_selector_scenario" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
```

Scripted audit bank:

```powershell
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-approval-audit/artifacts" --no-daemon
```

Focused live GPT-OSS replay:

```powershell
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditMode=live" "-PapprovalAuditConfig=$env:USERPROFILE\.talos\config.yaml" "-PapprovalAuditScenario=static-web-selector-script-only-verified" "-PapprovalAuditArtifactsRoot=local/manual-testing/synchronized-approval-live-gptoss-t332-20260520-r1" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/synchronized-approval-live-gptoss-t332-20260520-r1" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/synchronized-approval-live-gptoss-t332-20260520-r1,local/manual-workspaces/synchronized-approval-live-gptoss-t332-20260520-r1" --no-daemon
```

Live transcript outcome:

```json
"approvalDescriptions" : [ "write operation: talos.edit_file" ],
"traceStatus" : "PARTIAL",
"verificationStatus" : "PASSED",
"verificationSummary" : "Static web coherence checks passed for 1 mutated target(s).",
"checkpointStatus" : "CREATED"
```

The live model first attempted an irrelevant `script_fixed.js` write, which was blocked before approval by the expected-target guard, then recovered and edited `script.js`. This is acceptable for T332 because the original high-severity failure was the approved `rename_path` workspace damage path. It remains a quality signal for future tool-use prompting, but it is not a T332 release blocker because no wrong-target mutation was approved and the final workspace state passed static verification.
