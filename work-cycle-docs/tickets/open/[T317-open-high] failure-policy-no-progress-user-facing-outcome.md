# T317 - Failure Policy No-Progress Outcome Is Too Opaque

Status: fixed in working tree for no-progress runtime context; broader outcome polish remains open
Severity: high
Release gate: yes for live audit clarity
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

When Talos repeatedly read/listed files after a mutation-like prompt, it stopped with:

```text
[Tool loop stopped by failure policy: failure policy stopped the tool loop after 3 consecutive no-progress iteration(s). Review the latest tool errors before retrying.]
```

The answer was truthful but not useful. It did not tell the user that the runtime had classified the prompt as read-only and had hidden mutating tools from the model.

## Expected Behavior

For failure-policy stops, final output should include the actionable runtime cause when available:

- current task contract
- mutation allowed or not
- visible tool surface
- last repeated failure
- whether a classification/tool-surface mismatch is likely

## Proposed Tests

- `ToolCallLoopTest.readOnlyDuplicateReadLoopStopsBeforeGenericIterationLimit`
- Future: `failurePolicyStopOnMutationLikePromptReportsReadOnlyContract`
- Future: `failurePolicyStopOnMissingPathReportsMissingPathAndInspectedFiles`
- `failurePolicyStopDoesNotClaimTaskCompletion`

## Related Findings

- T315 fixed one source of this failure by making the transcript's follow-up creation prompt mutation-capable.
- No-progress failure-policy output now includes runtime context: task contract, mutationAllowed state, successful mutation count, and a hint when mutating tools were unavailable.

Focused evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyDuplicateReadLoopStopsBeforeGenericIterationLimit" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.capability.CapabilityProfileRegistryTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest" --no-daemon
```

All passed on 2026-05-19.

Remaining open scope: tune missing-path-specific no-progress wording if a future transcript shows the generic runtime context is still insufficient.
