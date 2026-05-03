# T101 - Current-Turn Mutation Retry Must Not Reissue Stale Request

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: T100 focused clean Qwen/GPT-OSS re-audit

## Evidence Summary

- Audit root:
  `local/manual-testing/t100-focused-clean-audit-20260503-154258`
- Findings:
  `local/manual-testing/t100-focused-clean-audit-20260503-154258/FINDINGS-T100-FOCUSED-TWO-MODEL.md`
- Qwen transcript:
  `local/manual-testing/t100-focused-clean-audit-20260503-154258/TEST-OUTPUT-QWEN-14B.txt`

Observed:

- The user made a fresh explicit mutation request:
  `Create a complete static BMI calculator in this folder with index.html,
  styles.css, and scripts.js.`
- The current-turn prompt frame was correct: `FILE_CREATE`,
  `mutationAllowed=true`, and `[ExpectedTargets] requiredTargets:
  index.html, styles.css, scripts.js`.
  - Evidence: `TEST-OUTPUT-QWEN-14B.txt:1159-1180`
- After the model initially failed to issue write/edit tools, Talos generated a
  retry prompt that said the current user message was the BMI create request,
  but also said:
  `The previous mutation request to reissue is: Make script.js fix the selector
  bug by changing .missing-button to .cta-button.`
  - Evidence: `TEST-OUTPUT-QWEN-14B.txt:1558-1588`
- The model then acted on stale `script.js` instead of the current BMI target
  set, and the turn ended `BLOCKED (BLOCKED_BY_POLICY)`.
  - Evidence: `TEST-OUTPUT-QWEN-14B.txt:1271`

## Problem

The initial mutation no-tool retry path can choose an older incomplete mutation
request as the retry target even when the current user turn is itself a fresh,
explicit mutation request with explicit expected targets.

That gives the model contradictory runtime guidance:

- Current-turn frame: mutate `index.html`, `styles.css`, and `scripts.js`.
- Retry prompt: reissue older selector-fix mutation for `script.js`.

This is a runtime retry-context selection bug, not a
`CurrentTurnCapabilityFrame` prompt construction bug.

## Scope

- Inspect the mutation no-tool retry path in `AssistantTurnExecutor`,
  especially the code that builds the retry/follow-up prompt after a
  mutation-capable turn returns no write/edit calls.
- When the current user turn has an explicit mutation contract and current
  expected targets, the retry prompt must reissue the current user request, not
  an older mutation request from history.
- Previous incomplete mutation requests may still be used for natural repair
  follow-ups when the current user message is ambiguous, such as
  `try again`, `fix it`, or `review and fix`.
- Preserve T100 behavior where `Action obligation failed` keeps follow-up
  classification mutation-capable.

## Non-Goals

- No new broad memory or planner.
- No prompt wording changes to `CurrentTurnCapabilityFrame`.
- No provider forced-tool-choice work.
- No static web verifier changes unless directly needed for a focused test.

## Acceptance Criteria

- A fresh explicit mutation request after an incomplete older mutation produces
  a no-tool retry prompt whose reissued mutation request is the current user
  request.
- The retry prompt does not contain an older unrelated mutation request as
  `The previous mutation request to reissue is`.
- Existing natural repair follow-ups still inherit the previous mutation
  contract where appropriate.
- Tests cover a `script.js` older failure followed by a fresh explicit
  `scripts.js` create request.
- No regression to T99/T100 pending-obligation failure dominance.

## Suggested Tests

- Unit or integration test around the retry-prompt builder:
  - history contains failed `Make script.js fix...`
  - current user asks `Create ... index.html, styles.css, scripts.js`
  - model returns no write/edit calls
  - retry prompt names the current BMI request as the action to perform and
    does not reissue the stale `script.js` request.
- Existing repair-follow-up test:
  - after `Action obligation failed`, `Review ... and fix ...` remains
    `FILE_CREATE` / mutation-capable.
- Focused e2e if available:
  - scripted no-tool first response for a fresh explicit create after stale
    failure should not mutate the stale target on retry.

## Verification

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
./gradlew.bat e2eTest --no-daemon
```

After implementation, rerun:

```text
local/manual-testing/t100-focused-clean-audit-20260503-154258/PROMPTS-T100-FOCUSED-TWO-MODEL.md
```

## Implementation Result

- `AssistantTurnExecutor` now only includes `The previous mutation request to
  reissue is` in the missing-mutation retry prompt when the current contract is
  an inherited repair follow-up.
- Fresh explicit mutation turns now retry the current user request directly,
  even if history contains an older incomplete mutation.
- Ambiguous repair follow-ups such as `Review ... and fix ...` can still
  reissue the previous mutation request.

## Verification Run

- `./gradlew.bat test --tests "*mutationRetryForFreshExplicitRequestDoesNotReissueOlderMutationRequest" --no-daemon`
  - First run failed before the fix because the retry prompt included the stale
    `script.js` request.
  - Passed after the fix.
- `./gradlew.bat test --tests "*mutationRetryForFreshExplicitRequestDoesNotReissueOlderMutationRequest" --tests "*mutationRetryForRepairFollowUpCanReissuePreviousMutationRequest" --no-daemon`
- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`
- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon`
- `./gradlew.bat e2eTest --no-daemon`
- `./gradlew.bat clean test e2eTest installDist --no-daemon`
- `python local/manual-testing/t101-focused-clean-audit-20260503-161159/run_t101_focused_two_model_audit.py`
  - Findings:
    `local/manual-testing/t101-focused-clean-audit-20260503-161159/FINDINGS-T101-FOCUSED-TWO-MODEL.md`
  - Qwen live path confirmed the fresh BMI retry prompt used the current BMI
    request and did not reissue the stale `script.js` selector request.
  - Repair-follow-up retry still reissued the previous BMI create request, as
    intended.
