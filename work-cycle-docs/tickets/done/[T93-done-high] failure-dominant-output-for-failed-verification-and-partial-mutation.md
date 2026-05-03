# T93 - Failure-Dominant Output For Failed Verification And Partial Mutation

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: Clean Qwen/GPT-OSS audit follow-up

## Evidence Summary

- Source: clean two-model manual audit
- Date: 2026-05-03
- Models:
  - Qwen: `ollama/qwen2.5-coder:14b`
  - GPT-OSS: `ollama/gpt-oss:20b`
- Audit root: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152`
- Raw transcript: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/TEST-OUTPUT-QWEN-14B.txt`
- Findings: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/FINDINGS-CLEAN-TWO-MODEL.md`
- Verification status: Qwen first BMI create failed static verification.

Observed evidence:

- Qwen first BMI create failed static verification around
  `TEST-OUTPUT-QWEN-14B.txt:1869`.
- The same visible answer later said the script was updated successfully and
  began manual instructions around `TEST-OUTPUT-QWEN-14B.txt:1884`.
- The same visible answer said the files should be saved and the calculator was
  complete around `TEST-OUTPUT-QWEN-14B.txt:1987`.

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Secondary buckets:

- `VERIFICATION`
- `REPAIR_CONTROL`

Blocker level: release blocker

Why this level:

Failed verifier and partial mutation turns must be failure-dominant. A runtime
failure block followed by model-authored success or manual "save these files"
instructions can make a failed task look usable.

## Architectural Hypothesis

The runtime already detects failed verification, but the final visible renderer
still allows model-authored prose after the failure block. Outcome dominance
needs to be enforced at the renderer boundary for failed verifier and partial
mutation outcomes, not left to the model.

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- runtime outcome rendering and verification summary code
- focused assistant turn executor tests

## Goal

When runtime verification fails, or mutation is partial or blocked, final
visible output must not include model-authored success claims, "complete",
"ready to use", "open in browser", or manual "save these files" prose after the
failure block.

## Non-Goals

- No LLM classifier for outcome truth.
- No broad rewrite of assistant prose for verified successful outcomes.
- No full T61-style audit as part of this individual ticket.

## Implementation Notes

Prefer deterministic runtime ownership. Failed or partial mutation outcomes
should replace or sanitize assistant prose so the user sees a concise
failure-dominant summary. Successful verified outputs should still preserve
concise success summaries.

Implemented:

- Replaced the non-partial failed-static-verification append path with a
  runtime-owned failure summary in `ExecutionOutcome`.
- The replacement names the failed verifier summary, unresolved static problems,
  and applied mutating tool calls without appending model-authored success or
  manual browser/save instructions.
- Existing partial mutation summaries remain runtime-owned and continue to be
  shown under the partial verification failure block.
- Verified successful outputs still retain concise assistant success summaries.

## Acceptance Criteria

- Done: failed verifier output is failure-dominant.
- Done: success/manual prose after failed verification is suppressed or
  replaced.
- Done: tests cover model text containing success prose after failed
  verification.
- Done: existing successful verified outputs still preserve concise success
  summaries.
- Done: no regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit/integration test: failed verifier or partial mutation answer containing
  success/manual prose is rendered failure-dominant.
- Neighbor test: verified success answer keeps concise success content.

Added:

- `ExecutionOutcomeTest.failedStaticVerificationReplacesSuccessAndManualProse`
- Strengthened `ExecutionOutcomeTest.literalMatchAfterSuccessfulWriteIsVerifiedComplete`
- Updated `ExecutionOutcomeTest.postApplyBroadWebAppMissingScriptIsDowngradedAsIncomplete`
  to assert runtime-owned applied mutation facts instead of appended success
  prose.

Commands:

```powershell
./gradlew.bat test --tests "*AssistantTurnExecutorTest*" --no-daemon
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
```

Verification run:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.failedStaticVerificationReplacesSuccessAndManualProse" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```

Result: all commands passed after the implementation. The new regression failed
before the implementation because the final answer still contained
`calculator is complete`.

## Work-Test Cycle Notes

- Use the inner dev loop for T93.
- Do not run the clean two-model milestone audit after this ticket alone.
- Re-run the clean Qwen/GPT-OSS audit after the T93-T95 batch passes normal
  verification.

## Known Risks

- Over-sanitizing could erase useful model explanations on genuinely successful
  verified outputs. Covered by a verified-success neighbor assertion.
- Under-sanitizing leaves misleading success prose after a failed runtime
  outcome. Covered by the new failed-verifier regression.

## Known Follow-Ups

- If sanitizer logic needs many ad hoc phrases, split outcome rendering into a
  clearer runtime-owned failure renderer.
