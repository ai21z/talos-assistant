# [T174-done-medium] Separate Read-Only Evidence Answer State From Post-Apply Verification

Status: done
Priority: medium

## Evidence Summary

- Source: manual llama.cpp T61-I full audit
- Date: 2026-05-06
- Branch: v0.9.0-beta-dev
- Models/backends: llama_cpp/qwen2.5-coder-14b, llama_cpp/gpt-oss:20b

Observed behavior:

```text
Read-only verify/status prompts emitted:
[Task not verified: verification was required for this turn, but no task verifier ran.]

The warning is confusing for evidence-grounded read-only answers. It is tied to post-apply mutation verification,
not to whether the read-only answer inspected evidence.
```

Expected behavior:

```text
Read-only verify/status turns should distinguish evidence-grounded answers from post-apply task verification.
If evidence was inspected, the output should not use the mutation-oriented "task verifier did not run" warning.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `VERIFICATION`
- `TOOL_SURFACE`

Blocker level:

- candidate follow-up

## Resolution

Read-only verify/status turns now use the evidence gate as the controlling state for whether an answer is grounded enough to return.

When a non-mutating `VERIFY_ONLY` turn satisfies the required evidence obligation, `verificationStatus=NOT_RUN` no longer downgrades the outcome to advisory or injects the old post-apply verifier banner. The result is classified as `READ_ONLY_ANSWERED`.

When a read-only verify/status turn gathers no required evidence, the output remains advisory and evidence-incomplete. It no longer adds the misleading post-apply verifier wording.

Mutation/apply verification annotations remain governed by post-apply static verification and readback behavior.

## Verification

Passed:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest.verificationRequiredReadOnlyWithEvidenceIsReadOnlyAnsweredWhenPostApplyVerifierDidNotRun" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest.verificationRequiredReadOnlyWithMissingEvidenceStaysAdvisory" --tests "dev.talos.cli.modes.ExecutionOutcomeTest.verificationRequiredReadOnlyWithEvidenceButNoPostApplyVerifierIsReadOnlyAnswered" --tests "dev.talos.cli.modes.ExecutionOutcomeTest.verificationRequiredReadOnlyWithMissingEvidenceStillReportsIncompleteEvidence" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.OutcomeDominancePolicyTest --tests dev.talos.cli.modes.ExecutionOutcomeTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```
