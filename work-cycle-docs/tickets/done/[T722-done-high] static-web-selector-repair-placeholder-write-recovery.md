# T722 - Static-Web Selector Repair Placeholder Write Recovery

Status: done
Priority: high
Created: 2026-06-08
Source audit: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026`
Completed: 2026-06-08

## Problem

In the GPT-OSS `0.10.0` synchronized approval audit, scenario
`static-web-selector-script-only-verified` failed before approval. The model was
asked to read `script.js`, change `.missing-button` to `.cta-button`, and avoid
editing forbidden sibling `scripts.js`. It inspected the workspace and relevant
files, then emitted `talos.write_file` with `path=?` and `content=?`.

Runtime correctly blocked the invalid path before approval and no file changed,
but the repair did not converge on the required target `script.js`.

Evidence:

- Audit report: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/FINDINGS.md`
- Failure bundle: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/artifacts/gptoss/sync-approval/static-web-selector-script-only-verified/AUDIT-BUNDLE.md`
- Trace: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/artifacts/gptoss/sync-approval/static-web-selector-script-only-verified/traces/last-trace.txt`
- Final answer: `local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026/artifacts/gptoss/sync-approval/static-web-selector-script-only-verified/final-answer.txt`

Key trace facts:

- `TASK_CONTRACT_RESOLVED`: `FILE_EDIT`, `mutationAllowed=true`,
  `verificationRequired=true`.
- Required target: `script.js`; forbidden artifact: `scripts.js`.
- Runtime allowed read/list/grep, then compact mutation continuation retried.
- `TOOL_CALL_PARSED talos.write_file` with `pathHint=?`,
  `contentBytes=1`, `contentLines=1`.
- `TOOL_CALL_BLOCKED`: invalid path before approval; no file changed.

## Why It Matters

This is not an unsafe mutation defect: runtime blocked the placeholder path
before approval. It is still a P1 release-gate failure because a simple
selector repair did not reach approval or mutate the required target. The
current repair/continuation path does not recover from placeholder arguments in
a required-target static-web mutation.

## Acceptance Criteria

- Placeholder paths such as `?`, empty path, or obvious placeholder content in a
  required-target static-web mutation trigger a compact target-specific retry or
  deterministic failure that names `script.js` as the only valid target.
- The retry frame preserves forbidden sibling constraints such as `scripts.js`.
- The scenario does not report an approval failure when no approval prompt was
  appropriate because the tool call was invalid before approval.
- `static-web-selector-script-only-verified` reaches approval and verification,
  or fails with a deterministic model/tool-call-convergence classification.

## Regression Test

Add deterministic coverage in the tool-call loop or synchronized approval
harness:

- A static-web selector repair with required target `script.js` and forbidden
  target `scripts.js` rejects `write_file(path="?")` before approval.
- The rejection path produces a concrete retry/failure instruction containing
  `script.js`, excluding `scripts.js`.
- No file changes occur for placeholder arguments.

## Implementation Evidence

- `StaticWebRepairPathGuard` now treats obvious placeholder paths such as `?`,
  `<path>`, `[filename]`, `path`, `file`, and `todo` as invalid static-web
  expected-target scope writes before approval.
- The existing expected-target scope repair lane then recovers the selector case
  through the runtime-owned exact replacement path when the request provides a
  concrete replacement (`.missing-button` -> `.cta-button`).
- Regressions:
  - `StaticWebRepairPathGuardTest.rejectsPlaceholderWritePathBeforeApprovalForStaticWebTargetSet`
  - `ToolCallLoopTest.staticWebPlaceholderWritePathRepairsToExactExpectedSelectorTarget`
- Verification:
  - `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.toolcall.StaticWebRepairPathGuardTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon`
  - `.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon`

