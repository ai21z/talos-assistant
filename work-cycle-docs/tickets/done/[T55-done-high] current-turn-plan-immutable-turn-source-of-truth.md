# [T55-done-high] CurrentTurnPlan Immutable Turn Source Of Truth

Status: done
Priority: high

## Evidence Summary

- Source: T54 prompt audit re-evaluation
- Date: 2026-04-30
- Branch / commit: `v0.9.0-beta-dev` / `50efcb7`
- Raw transcript path: `local/manual-workspaces/t54-audit-20260430-105839/TEST-OUTPUT-T54.txt`
- Design spec: `docs/superpowers/specs/2026-04-30-t54-control-plane-roadmap-design.md`

Observed failures:

- Exact literal README write appears to lose verification after the T48
  mutating-tool retry path.
- `ExecutionOutcome.fromToolLoop` and no-tool paths re-derive task contract from
  mutable `messages`.
- Retry helpers append synthetic assistant/user messages before later logic
  resolves contract, expectation, grounding, or outcome state.

## Classification

Primary taxonomy bucket: `CURRENT_TURN_FRAME`

Secondary buckets:

- `ACTION_OBLIGATION`
- `VERIFICATION`
- `OUTCOME_TRUTH`
- `TRACE_REDACTION`

Blocker level: release blocker foundation

Why this level:

All later obligation and outcome work depends on a stable current-turn source of
truth. Without it, retries can change the meaning of the turn after the runtime
has already selected tools and obligations.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Fix exact literal writes after retry.
```

Architectural hypothesis:

```text
Talos needs an immutable CurrentTurnPlan created once per user turn. The plan
should hold original request, task contract, phase, action obligation, evidence
obligation placeholder, output obligation placeholder, expected/forbidden
targets, literal expectations, tool surface, protected-resource intent, verifier
profile placeholder, active-task placeholder, and prompt-audit identifiers.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/task/TaskContract.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/expectation/TaskExpectationResolver.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/main/java/dev/talos/runtime/trace/PromptAuditSnapshot.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java`

## Goal

Create and thread an immutable current-turn record through executor, prompt
audit, retry, verification, and outcome code so core turn facts are not
re-derived from mutated `messages`.

## Non-Goals

- No full `TaskIntentPolicy` split in this ticket.
- No full `EvidenceObligationPolicy` implementation beyond explicit placeholder
  fields.
- No verifier or repair profile extraction.
- No shell/browser/MCP/multi-agent behavior.
- No version bump or changelog update.

## Implementation Notes

- Add a small `CurrentTurnPlan` record under a runtime package.
- Build it once near the start of `AssistantTurnExecutor.execute`.
- Keep `TaskContract` as an input field for the first pass.
- Include `ActionObligation` and selected `ExecutionPhase`.
- Include literal task expectations resolved from the original user request.
- Include visible tool names after native tool surface selection.
- Make prompt audit render from the plan.
- Add overloads or narrow adapters so `ExecutionOutcome` consumes the plan
  rather than re-running `TaskContractResolver.fromMessages`.
- Keep placeholder fields honest: evidence/output/profile/context fields may be
  `NONE_OR_NOT_DERIVED` until later tickets.

## Acceptance Criteria

- `CurrentTurnPlan` is built once per user turn from the original request and
  selected runtime state.
- Mutating-obligation retry messages do not change contract, targets, literal
  expectations, action obligation, or verifier applicability.
- Exact literal write expectations survive a no-tool mutation retry.
- Prompt audit task, obligation, tools, phase, and placeholder fields come from
  `CurrentTurnPlan`.
- `ExecutionOutcome` no longer resolves core task facts from mutated messages
  when a plan is available.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `CurrentTurnPlan` fields are immutable and derived from the
  original user request.
- Unit test: retry-appended messages do not alter exact literal expectations.
- Executor test: prompt audit reflects plan fields after frame injection.
- Outcome test: `ExecutionOutcome` uses the plan contract instead of mutated
  messages.

Manual/TalosBench rerun:

- Prompt family: exact literal write after obligation retry.
- Workspace fixture: single `README.md` or `index.html` with `BEFORE`.
- Expected trace: original contract `FILE_EDIT`, obligation
  `MUTATING_TOOL_REQUIRED`, exact expectation retained.
- Expected outcome: mismatch fails, match verifies.

Commands:

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version.
- Do not update `CHANGELOG.md`.
- Keep the first implementation narrow enough that T56 and T57 can extend it
  without rewriting it.

## Implementation Summary

- Added immutable `CurrentTurnPlan` as the current-turn source of truth for:
  - original user request
  - task contract
  - initial/final phase
  - action obligation
  - literal task expectations
  - native/prompt/blocked tool surfaces
  - explicit placeholder fields for evidence/output/context/artifact/profile
- Updated current-turn capability frame rendering to consume the plan.
- Updated prompt audit snapshot generation to render from the plan while keeping
  placeholder fields honest and redacted.
- Built the plan once near the start of `AssistantTurnExecutor.execute`, after
  contract resolution, phase initialization, and native tool-surface selection.
- Threaded the plan through no-tool retry, mutation retry, inspection retry,
  fallback plan, tool-loop shaping, no-tool shaping, and static verification
  paths that previously re-read mutable message history.
- Added plan-aware `ExecutionOutcome` overloads and kept legacy overloads as
  compatibility adapters.
- Added plan-aware denied/invalid mutation classification so retry-appended
  synthetic user messages cannot hide the original mutation obligation.
- Restored the fake protected-path `.env` e2e fixture and explicitly allowlisted
  tracked fake fixture `.env` files in `.gitignore`; this was found during full
  T55 closeout verification.

## Files Changed

- `.gitignore`
- `src/e2eTest/resources/fixtures/protected-path/.env`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/main/java/dev/talos/runtime/trace/PromptAuditSnapshot.java`
- `src/main/java/dev/talos/runtime/turn/CurrentTurnPlan.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/test/java/dev/talos/runtime/trace/PromptAuditSnapshotTest.java`
- `src/test/java/dev/talos/runtime/turn/CurrentTurnPlanTest.java`

## Tests / Evidence Completed

- `.\gradlew.bat test --tests dev.talos.runtime.turn.CurrentTurnPlanTest --tests dev.talos.runtime.trace.PromptAuditSnapshotTest --tests dev.talos.cli.modes.ExecutionOutcomeTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest --tests dev.talos.cli.modes.AssistantTurnExecutorPhasePolicyTest --no-daemon` - PASS
- `.\gradlew.bat test --no-daemon` - PASS
- `.\gradlew.bat e2eTest --no-daemon` - initially failed on scenarios 65 and
  66 because the protected-path fixture expected `.env` but the fake fixture was
  missing and `*.env` was globally ignored.
- `.\gradlew.bat e2eTest --tests dev.talos.harness.JsonScenarioPackTest.protectedPathMutationDeniedBeforeApproval --tests dev.talos.harness.JsonScenarioPackTest.protectedReadRequiresApproval --no-daemon` - PASS after restoring the fixture.
- `.\gradlew.bat e2eTest --no-daemon` - PASS after restoring the fixture.
- `.\gradlew.bat check --no-daemon` - PASS
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - PASS; validated 10 TalosBench cases.

## Review Evidence

- Task 1 spec review: APPROVED.
- Task 1 code quality review: APPROVED.
- Task 2 spec review: APPROVED after prompt-audit placeholder hardening.
- Task 2 code quality review: APPROVED.
- Task 3 spec review: APPROVED after fallback frame preservation.
- Task 3 code quality review: APPROVED.
- Task 4 spec review: APPROVED after denied/invalid mutation classification
  stopped reading retry-mutated latest user messages.
- Task 4 code quality review: APPROVED.

## Known Risks

- A giant plan object can become an executor in disguise. Keep it a data record.
- Threading the plan through existing methods may be noisy; prefer small
  overloads over broad rewrites.

## Known Follow-Ups

- T56 adds stronger intent and conversation boundary fields.
- T57 adds real evidence obligations.
- T58 adds outcome dominance over plan obligations.
