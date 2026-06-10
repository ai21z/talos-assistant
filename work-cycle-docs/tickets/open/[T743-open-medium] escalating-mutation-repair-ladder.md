# T743 - Escalating Mutation Repair Ladder

Status: open
Severity: medium
Release gate: supports T280/T284 bank stability (rescues residual emission failures)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

The repair ladder skips exactly the failure modes observed in the failed
banks, and the one retry that does fire re-sends an identical unconstrained
request. Incentives are inverted: the model that ATTEMPTED a tool call (r1,
malformed payload) got zero retries, while the do-nothing response (r3) got
one retry that changed nothing about the constraint envelope.

## Evidence Analysis

- Malformed-debris bypass: `cli/modes/AssistantTurnExecutor.java:599-603` —
  `looksLikeMalformedProtocolArrayDebris/looksLikeMalformedToolProtocol`
  answers are shaped into a no-action notice and returned BEFORE
  `mutationRequestRetryIfNeeded` at line 606. r1 trace confirms:
  `PROTOCOL_SANITIZED` → `OUTCOME_RENDERED FAILED`, no retry events.
- Retry suppression gates: `cli/modes/MissingMutationRetry.java:97-98` —
  `failureDecision().shouldStop()` and `hasInvalidMutatingFailure` both return
  early; an almost-valid `operations_json` failing with INVALID_PARAMS gets no
  re-prompt.
- Unescalated retry: the retry chat call (MissingMutationRetry.java:140-141)
  obtains controls via `AssistantTurnExecutor.chatControlsForTurn` (1162-1174)
  → same `ProviderRequestControlPolicy` decision → pre-T739 that meant the r3
  retry went out with identical AUTO + default sampling (r3's provider body IS
  the retry request).
- Standard practice (constrained re-ask per guidance/outlines; OpenAI
  structured-outputs retry guidance): escalate constraints on retry — harder
  tool choice, cooler sampling, echo the exact parse error.

## Architectural Hypothesis

The bounded fail-closed retry design is correct; its routing and escalation
are incomplete. Three bounded changes close the gaps without loosening
fail-closed behavior.

## Architecture Metadata

Capability: bounded mutation repair control
Operation(s): all mutating/workspace obligations
Owning package/class: `dev.talos.cli.modes.MissingMutationRetry`,
`dev.talos.cli.modes.AssistantTurnExecutor` (routing only)
New or changed tools: none
Risk, approval, and protected paths: unchanged (retries still pass the full
TurnProcessor gate sequence; approval still required for any mutation)
Checkpoint, evidence, verification, and repair:
  - Repair profile: still bounded (one retry per gap class); fail-closed
    preserved when retry also fails
Outcome and trace:
  - Trace/debug fields: retry events recorded with escalation tags
Refactor scope: the two named classes + tests

## Required Behavior

1. Debris routing: malformed-protocol answers route through
   `MissingMutationRetry` (when a mutation/workspace obligation is active)
   instead of returning at AssistantTurnExecutor:599-603; the no-action notice
   remains the final answer only if the retry also fails.
2. Escalated retry controls: the retry request upgrades to
   REQUIRED (or NAMED when one tool is visible) + temperature 0 (via T740
   `withSampling`), never re-sends identical controls.
3. INVALID_PARAMS: permit exactly one bounded retry when a mutating/workspace
   tool failed with invalid params, echoing the parser/tool error text into
   the retry frame; keep `shouldStop` honored for failure-policy stops.

## Non-Goals

- No unbounded retry loops; no second retry per class.
- No changes to fail-closed final-answer rendering.

## Tests

- `MissingMutationRetryTest`: INVALID_PARAMS single-retry path (error echoed
  in retry frame); shouldStop still suppresses; retry controls escalated
  (REQUIRED/NAMED + temp 0).
- ATE-path test: malformed-debris answer with active workspace obligation
  triggers retry; without obligation, current behavior preserved.
- Regression: existing MissingMutationRetry behaviors unchanged.

## Acceptance Criteria

- Focused tests green:
  `./gradlew.bat test --tests "dev.talos.cli.modes.MissingMutationRetryTest" --no-daemon`
  plus the ATE routing tests.
- Trace for a debris-retry turn shows the retry event with escalation tags.
- CHANGELOG `## [Unreleased]` gains a T743 entry.

## Known Risks

- ATE is a 3,305-line hub (Wave 5 unwinds it) — keep the routing change
  minimal and covered by tests; no opportunistic refactoring here.

## 2026-06-10 completion evidence

- Implemented: (a) malformed-debris answers on mutation/workspace-obligation
  turns route through one bounded MissingMutationRetry pass in
  `AssistantTurnExecutor.resolveNoToolAnswer`; if the retry does not produce a
  successful mutation, the original no-action notice is preserved (existing
  malformed-protocol test stays green); (b) the retry chat binding escalates
  via `chatFullEscalatedRetry` (obligation tool-choice envelope + temperature
  pinned to 0 over NEAR_GREEDY); (c) genuinely invalid mutating parameters get
  one corrected retry with the tool error echoed into the re-prompt.
- Scope refinement found during testing: pre-approval policy rejections
  (sandbox escape "Path not allowed before approval", source-evidence
  "blocked before approval", forbidden targets) reuse INVALID_PARAMS and are
  EXCLUDED from the generic corrected retry — the deterministic e2e scenario
  pack (scenario 28 path-escape) and the source-evidence executor test caught
  the over-broad first version; discriminator: pre-approval rejections
  consistently carry "before approval" in their messages.
- Tests green: new `AssistantTurnExecutorDebrisRetryTest` (corrected second
  response rescues the turn end-to-end), `MissingMutationRetryTest` +2
  (invalid-params echo retry; denied-mutation suppression), updated
  `mutationRetryDoesNotFireAfterInvalidMutatingArgs` to the new contract.
- Full `test` + `e2eTest` lanes BUILD SUCCESSFUL (2m17s, 4797 unit + 168 e2e).
