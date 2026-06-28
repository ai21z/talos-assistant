# T764 - Workspace-Operation Approval Scenarios Never Claim The Rendered Outcome

Status: done - completed in wave 2; see completion evidence section
Severity: medium
Release gate: no (harness claim hardening; closes the observability gap that
let T763 pass packet gates silently)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: Claude

## Problem

The synchronized-approval workspace-operation scenarios in
`SynchronizedApprovalAuditMain` (`runWorkspaceCopyPathApproved`,
`runWorkspaceMovePathApproved`, `runWorkspaceRenamePathApproved`,
`runWorkspaceDeletePathApproved`, plus the mkdir and batch-apply siblings;
both scripted and live `LlmClient` overloads) claim tool usage
(`requireToolUsed`) and resulting file state, but never claim the rendered
outcome. A turn that approves, executes, and checkpoints the operation and
then fail-closes with
`OUTCOME_RENDERED {status=BLOCKED, classification=BLOCKED_BY_POLICY}` still
passes the scenario.

That is exactly how T763's phantom expected-target bug stayed invisible: all
four approved copy/move/rename/delete live lanes rendered BLOCKED across the
0.10.2 packet, the 0.10.3 packet, and the wave-1 stabilization run (12
traces, e.g.
`local/manual-testing/current-0.10.3-release-packet-20260611-125134/artifacts/qwen/sync-approval/workspace-*-approved/traces/last-trace.txt`)
while every scenario reported green. The harness verified that the file
moved, not that the user was told the task completed.

## Design

- Characterization first: pin the claim mechanics in
  `SynchronizedApprovalAuditRunnerTest` against constructed traces - a
  BLOCKED/BLOCKED_BY_POLICY outcome (the exact packet regression shape) and a
  BLOCKED/BLOCKED_BY_APPROVAL outcome must fail the claim;
  COMPLETE (verified and unverified) and PARTIAL outcomes must pass, so the
  claim cannot overreach into the legitimate runtime-repair lane. Also pin,
  end to end, that the six scripted workspace lanes currently render an
  un-BLOCKED `OUTCOME_RENDERED` line in `traces/last-trace.txt` (true after
  T763's fix; this is the behavior being locked in).
- New claim helper `requireOutcomeNotBlocked` in
  `SynchronizedApprovalAuditMain`, package-private for the characterization
  tests, reading the structured `LocalTurnTrace.OutcomeSummary` (not text
  scraping, so it is immune to event-map rendering order). It fails with the
  rendered `OUTCOME_RENDERED {status=..., classification=...}` shape in the
  message so a failure maps directly onto the trace artifact line.
- Wire the claim into all six workspace-operation scenarios - copy, move,
  rename, delete, and also mkdir and batch-apply for uniformity - in both the
  scripted and live overloads: scripted lanes claim alongside the existing
  file-state checks; live lanes claim inside the existing
  `writeFailureMarker` try-block, after tool-usage and file-state checks so
  the most specific diagnostic fires first (the T763 shape executes the
  operation before blocking, so file state passes and the outcome claim is
  what fails).
- Claim is scoped to "does not render BLOCKED". It deliberately does not
  require COMPLETE: live lanes may legitimately render PARTIAL with runtime
  repair, and the summary scorer already owns that judgment.

## Behavioral delta (intended)

No runtime behavior change. The harness contract for approved
workspace-operation lanes tightens: a regression that fail-closes an
approved-and-executed workspace operation (T763's shape, or any future one)
now fails the scenario - scripted lanes fail `gradlew e2eTest`
deterministically, live lanes write a `FAILURE.md` bundle - instead of
passing on tool-usage and file-state checks alone.

## Architecture Metadata

Capability: e2e harness scenario claims (synchronized approval audit bank)
Operation(s): none (test/harness only; covers mkdir/copy/move/rename/delete/
batch lanes)
Owning package/class: `dev.talos.harness.SynchronizedApprovalAuditMain`
New or changed tools: none
Risk, approval, and protected paths: n/a (no production code touched)
Checkpoint behavior: unchanged
Evidence obligation: scenarios now require the rendered outcome to be
un-BLOCKED in addition to tool usage and file state
Verification profile: unchanged
Repair profile: unchanged (PARTIAL outcomes still pass the claim)
Outcome and trace: no event shapes change; the harness now reads
`LocalTurnTrace.outcome()` as claim input
Allowed refactor scope: the new claim helper and its twelve call sites in
`SynchronizedApprovalAuditMain`, plus characterization tests in
`SynchronizedApprovalAuditRunnerTest` only

## Acceptance Criteria

- A constructed BLOCKED/BLOCKED_BY_POLICY result fails
  `requireOutcomeNotBlocked` with the OUTCOME_RENDERED shape in the message;
  BLOCKED_BY_APPROVAL also fails; COMPLETE and PARTIAL pass.
- All six scripted workspace-operation lanes assert an un-BLOCKED
  `OUTCOME_RENDERED` line in the written trace artifact.
- Both scripted and live overloads of all six workspace-operation scenarios
  call the claim.
- `./gradlew.bat test e2eTest --no-daemon` green.
- CHANGELOG Unreleased entry added.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

- `SynchronizedApprovalAuditRunnerTest`:
  - claim-mechanics characterization against constructed
    `LocalTurnTrace` outcomes (BLOCKED_BY_POLICY fails, BLOCKED_BY_APPROVAL
    fails, COMPLETE/PARTIAL pass);
  - `deterministic_audit_entrypoint_can_run_single_workspace_operation_scenarios`
    extended to read each lane's `traces/last-trace.txt` and require a
    present, un-BLOCKED `OUTCOME_RENDERED` line.
- `gradlew test e2eTest --no-daemon` green.

## 2026-06-11 completion evidence

- Claim helper `requireOutcomeNotBlocked` added to
  `SynchronizedApprovalAuditMain` (package-private, structured
  `LocalTurnTrace.OutcomeSummary` input, OUTCOME_RENDERED-shaped failure
  message) and wired into all twelve workspace-operation call sites
  (mkdir/copy/move/rename/delete/batch-apply × scripted/live).
- Characterization tests in `SynchronizedApprovalAuditRunnerTest`:
  BLOCKED_BY_POLICY (the exact 12-trace packet regression shape) and
  BLOCKED_BY_APPROVAL constructed outcomes fail the claim with the rendered
  OUTCOME_RENDERED shape in the message; COMPLETE (verified/unverified) and
  PARTIAL pass, keeping the runtime-repair lane unclaimed. The
  single-scenario entrypoint loop now also requires a present, un-BLOCKED
  `OUTCOME_RENDERED` line in each lane's written `traces/last-trace.txt`,
  pinning end to end that approved workspace operations render un-BLOCKED
  after T763's fix.
- `gradlew test e2eTest --no-daemon` green (BUILD SUCCESSFUL; `:e2eTest`
  re-executed with the new claims - SynchronizedApprovalAuditRunnerTest
  39/39, 0 failures; `:test` UP-TO-DATE against the same-day T763 green run,
  unit inputs unchanged by this ticket).
- CHANGELOG Unreleased entry added under Changed.
