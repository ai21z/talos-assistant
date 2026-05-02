# [T78-done-high] Repair Follow-Up And Stale Outcome Hardening

Status: done
Priority: high
Date: 2026-05-02
Closed: 2026-05-02

## Evidence Summary

- Audit report:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/AUDIT-REPORT-FOCUSED.md`
- Raw transcript:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/TEST-OUTPUT-FOCUSED.txt`
- Failed web-create trace:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/trace-artifacts/000014-trc-46f98402-88f3-48f1-8b04-b7946c1bf2ff.json`
- Natural repair follow-up trace:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/trace-artifacts/000015-trc-0427a9bf-d503-43a0-8b62-0b6b53a379d0.json`

Observed sequence:

1. User asked Talos to create a static BMI calculator with `index.html`,
   `styles.css`, and `scripts.js`.
2. Talos mutated only `index.html`.
3. Static verification correctly failed because CSS/JS targets were not mutated.
4. User then asked:
   `Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.`
5. The follow-up was classified `READ_ONLY_QA`, made no tool calls, and surfaced
   prior mutation/output text as if it were the current answer.

## Goal

Recognize natural repair follow-up phrasing after incomplete verified mutation
outcomes and prevent prior mutation outcome text from being presented as a
current-turn mutation result when no current mutation ran.

## Non-Goals

- Do not make every read-only review prompt mutating.
- Do not weaken target overlap protections from T75.
- Do not hide prior verified failure status when the user asks about status.

## Implementation Notes

Likely owners:

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/cli/modes/UnifiedAssistantModeTest.java`

Root-cause hypothesis:

`TaskContractResolver.looksLikeRepairFollowUp` includes terse phrases such as
`fix it`, but not natural review/repair phrasing such as `fix any obvious issue`.
When inheritance is missed, `verifiedFollowUpSummaryIfNeeded` can surface prior
verified mutation text for a read-only/current no-tool turn.

## Acceptance Criteria

- After an incomplete static-verification mutation outcome, `Review the BMI
  calculator you just created and fix any obvious issue that would stop it from
  working in a browser.` inherits the prior mutating repair contract.
- The prompt surface includes mutating tools and static repair context.
- If a current turn performs no mutation, Talos must not present prior mutation
  success lines as current-turn changes.
- Existing explicit status-summary follow-ups still summarize prior verified
  outcomes truthfully.

## Required Tests

- Unit: task contract resolver inherits prior mutation contract for the natural
  repair phrase.
- Prompt-surface/unit: unified assistant mode exposes mutating tools for this
  phrase after incomplete BMI static-verification output.
- Unit: prior mutation outcome summaries are only used for status/summary
  questions, not repair-intent prompts.

## Closure Notes

- Added narrow repair-follow-up recognition for the audit phrasing
  `fix any obvious issue(s)` after an incomplete mutation outcome.
- Verified the inherited repair contract preserves the prior mutation targets
  and exposes write/edit tools with static verifier context.
- Added stale-success containment coverage: if the repair follow-up performs no
  current mutation, Talos returns the action-obligation failure instead of
  presenting stale success prose.

## Verification

- `.\gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon`
