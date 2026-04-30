# [T58-open-high] OutcomeDominancePolicy

Status: open
Priority: high

## Evidence Summary

- Source: T54 prompt audit re-evaluation
- Date: 2026-04-30
- Raw transcript path: `local/manual-workspaces/t54-audit-20260430-105839/TEST-OUTPUT-T54.txt`
- Design spec: `docs/superpowers/specs/2026-04-30-t54-control-plane-roadmap-design.md`

Observed failures:

- Failed `MUTATING_TOOL_REQUIRED` turns could render as
  `COMPLETE (READ_ONLY_ANSWERED)`.
- Exact literal write mutation could render as read-only answered after retry.
- `INSPECT_REQUIRED` with zero tools could complete.
- Protected read denial and failed obligations need one central final-status
  precedence model.
- Installed Talos 0.9.8 smoke run on 2026-04-30 showed
  `failed-static-verification-truth` ending with `COMPLETE (READ_ONLY_ANSWERED)`
  after repeated `WORKSPACE_ESCAPE` denials and failure-policy stop.
- The same smoke run showed `mutation-create-bmi` with `Last Turn` outcome
  `MUTATION_APPLIED` while `Local Trace` outcome was `FAILED (FAILED)` after
  static verification failed.

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Secondary buckets:

- `ACTION_OBLIGATION`
- `VERIFICATION`
- `PERMISSION`
- `TRACE_REDACTION`

Blocker level: release blocker

Why this level:

Users must be able to trust final status labels. A failed runtime obligation
cannot be hidden behind model prose.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Adjust this one final answer string.
```

Architectural hypothesis:

```text
Talos needs a central OutcomeDominancePolicy that takes CurrentTurnPlan,
tool-loop facts, evidence facts, approval facts, expectation verification, and
static verifier results, then returns the strongest final completion status and
warnings.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/outcome/`
- `src/main/java/dev/talos/runtime/policy/ResponseObligationVerifier.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`

## Goal

Centralize final status precedence so failed or blocked runtime obligations
always dominate completion labels, final annotations, task outcomes, and trace.

## Non-Goals

- No new classifier.
- No new capability system.
- No broad answer rewriting beyond truthful annotations/replacements needed to
  enforce runtime status.
- No change to approval policy except reflecting approval facts correctly.

## Implementation Notes

- Add a policy or small service that receives structured inputs rather than
  re-parsing answer text where possible.
- Preserve existing useful annotations, but have status selection happen once.
- Precedence should include:
  - invalid tool arguments;
  - protected read denial;
  - denied mutation;
  - read-only task attempted mutation;
  - missing mutating tool under `MUTATING_TOOL_REQUIRED`;
  - missing evidence under evidence obligation;
  - workspace/scope/sandbox denials such as `WORKSPACE_ESCAPE`;
  - repeated tool failure or failure-policy stop;
  - partial mutation;
  - exact expectation failure;
  - static verifier failure;
  - malformed protocol debris.
- Ensure `TaskCompletionStatus` and `/last trace` outcome agree.

## Acceptance Criteria

- Failed mutating obligation cannot render as `READ_ONLY_ANSWERED`.
- Failed evidence obligation cannot render as complete.
- Exact content verification failure dominates write/readback success.
- Protected read denial dominates model prose and does not leak content.
- Workspace escape, sandbox denial, approval denial, and failure-policy stop
  dominate model prose and cannot render as completed inspection.
- Static verifier failure dominates mutation-applied labels in every visible
  outcome surface.
- Partial mutation remains partial even if answer claims success.
- Trace outcome, task outcome, and final answer annotation agree.
- No regressions to existing denied mutation, invalid mutation, partial mutation,
  protected path, or static verification tests.

## Tests / Evidence

Required deterministic regression:

- Unit test: each dominance rule maps to the expected `TaskCompletionStatus`.
- Outcome test: no-tool failed mutation is blocked or failed, not read-only
  answered.
- Outcome test: missing evidence is advisory/failed according to T57 decision,
  not complete.
- Outcome test: failed verify-only run with only `WORKSPACE_ESCAPE` tool results
  is failed/not verified, not `READ_ONLY_ANSWERED`.
- Outcome test: static verifier failure cannot leave `Last Turn` as
  `MUTATION_APPLIED` while `Local Trace` says `FAILED`.
- Outcome test: exact literal mismatch after retry fails.
- Trace test: outcome fields match final status.

Manual/TalosBench rerun:

- Prompt family: failed no-tool mutation, protected read denial, exact literal
  mismatch, unsupported document read, failed static verification truth,
  natural BMI creation with verifier failure.
- Expected trace: strongest unmet obligation appears in warning/outcome.
- Expected outcome: no contradictory complete label.

Commands:

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Hardening pass, 2026-04-30:

- `OutcomeDominancePolicy` now maps non-mutating verification-required turns
  with `VerificationStatus.NOT_RUN` to `ADVISORY_ONLY`, not
  `READ_ONLY_ANSWERED`.
- `ExecutionOutcome` annotates those turns with an explicit `Task not verified`
  marker, including the missing-evidence path.
- Verified with `./gradlew.bat check --no-daemon` and full non-manual
  TalosBench against `build/install/talos/bin/talos.bat`; summary:
  `local/manual-testing/talosbench/20260430-230044/summary.md`.

## Known Risks

- If the dominance policy is too abstract, it may obscure why a turn failed.
  Preserve detailed warnings.
- Some existing tests may assert old wording. Update tests to assert status and
  essential wording rather than incidental prose.

## Known Follow-Ups

- T61 should add TalosBench assertions for final outcome dominance.
- Later capability profiles can add profile-specific verifier summaries without
  owning final truth precedence.
