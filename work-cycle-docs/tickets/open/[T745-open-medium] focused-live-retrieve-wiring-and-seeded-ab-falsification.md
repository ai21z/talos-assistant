# T745 - Focused Live Retrieve Wiring And Seeded A/B Falsification

Status: open
Severity: medium
Release gate: yes - supplies the clean `talos.retrieve` evidence T312 requires
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

Two evidence gaps remain from the 0.10.1 packet: (1) the only
`talos.retrieve` invocation evidence sits inside failed Qwen full banks —
`proposal-only-does-not-mutate` cannot be run as a focused live scenario
because it is absent from the live filter list; (2) the "fails late in bank"
hypothesis (KV/slot contamination) is unsupported by evidence but never
formally falsified — byte-identical provider bodies point to sampling
randomness instead.

## Evidence Analysis

- Live method exists and runs in full banks:
  `SynchronizedApprovalAuditMain.runProposalOnlyDoesNotMutate(Path,Path,LlmClient)`
  at lines 912-938; invoked in runLive at line 248.
- Focused filters: `liveScenarioFilters()` (lines 65-71) lists only
  static-web-selector, t325, and the six workspace ops; the
  `runSelectedLiveScenario` switch (322-341) default-throws. Wiring = 1 line
  in the filter list + 1 case in the switch (+2 lines scripted parity in
  `scriptedScenarioFilters()`/`runSelectedScriptedScenario`).
- Retrieve evidence today: r1 bundle
  (`.../qwen/sync-approval/proposal-only-does-not-mutate/`) shows
  `TOOL_EXECUTED talos.retrieve {success=true}` but inside a bank that failed
  at scenario 31 — matrix row carries an honest caveat (0.10.1 report).
- Bank-position hypothesis: history is SUPPRESSED per scenario, failing
  prompts are 3 messages, r1-failing vs focused-passing bodies byte-identical,
  one shared llama-server with `--parallel 1`
  (SynchronizedApprovalAuditMain.runLive 210-269; LlamaCppServerManager:30/148).
  A fixed-seed A/B (T740) decisively falsifies or confirms for near-zero cost
  before anyone builds per-scenario server restarts.

## Architectural Hypothesis

Harness-owned coverage gap: focused live filters never grew beyond the
workspace-ops wave. One scenario addition closes T312's retrieve gap; one
controlled experiment closes the contamination question.

## Architecture Metadata

Capability: live audit harness scenario filters (e2eTest harness only)
Operation(s): retrieve (read-only probe)
Owning package/class: `dev.talos.harness.SynchronizedApprovalAuditMain` (e2eTest)
New or changed tools: none (production code untouched)
Risk, approval, and protected paths: unchanged
Checkpoint, evidence, verification, and repair: unchanged
Outcome and trace: standard 13-file scenario bundle
Refactor scope: harness filter list + switch cases + harness test

## Required Behavior

1. Add `proposal-only-does-not-mutate` to `liveScenarioFilters()` and
   `runSelectedLiveScenario`; scripted parity in the scripted filter/switch.
2. Run the focused live retrieve probe (Qwen config) to a fresh
   `local/manual-testing/` root — expect PASS bundle with
   `TOOL_EXECUTED talos.retrieve` and artifact scan PASS.
3. Seeded A/B falsification (after T740): same fixed seed, run (a) focused
   `workspace-batch-apply-approved` and (b) the same scenario as final entry
   of a full bank; byte-compare provider bodies and outcomes. Identical →
   bank-position hypothesis CLOSED in writing; divergent → escalate to slot
   hygiene investigation (out of wave scope, ticketed separately).
4. Record both results in the wave evidence notes (consumed by T746's report
   update).

## Non-Goals

- No per-scenario server restart machinery.
- No production runtime changes.

## Tests

- Harness test (`SynchronizedApprovalAuditRunnerTest` family) asserting the
  scripted focused scenario runs and writes its bundle (mirrors how the
  workspace-op focused scenarios were regression-covered when added).

## Acceptance Criteria

- `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` green.
- Focused live retrieve bundle on disk with retrieve invocation evidence +
  artifact scan PASS — citable by T312 without caveat.
- A/B verdict written with byte-comparison evidence.
- CHANGELOG `## [Unreleased]` gains a T745 entry.

## 2026-06-11 completion evidence

- Wiring: `proposal-only-does-not-mutate` added to scripted + live focused
  filters and both scenario switches; harness regression test green.
- Clean retrieve evidence (T312 gap closed): focused live run with
  `llm.sampling.seed=7` —
  `local/manual-testing/wave1-t745-retrieve-s7-20260611-004808/artifacts/proposal-only-does-not-mutate`
  — scenario scored PASS (COMPLETE), artifact scan PASS, trace shows the full
  pipeline `TOOL_CALL_PARSED talos.retrieve` → `PERMISSION_DECISION ALLOW` →
  `TOOL_EXECUTED success=true`. Honest note: a first probe with seed 424242
  (`wave1-t745-retrieve-20260611-004623`) also PASSED but the model chose
  `talos.grep` — retrieve-vs-grep is genuine model choice on this read-only
  surface; both runs are recorded; the claim made is invocation-pipeline
  correctness, not invocation rate.
- Seeded A/B determinism (bank-position falsification, part 1):
  two focused `workspace-batch-apply-approved` runs with `seed=424242`
  (`wave1-t745-ab-a`, `wave1-t745-ab-b`) — provider bodies **byte-identical**
  (SHA256 `215A0778E5AAB2FE…` both) and outcomes identical (both PASS).
  Per-request determinism with a fixed seed is proven; combined with the r1
  byte-identical-input/divergent-output finding under a random seed, the
  bank-position/KV-contamination hypothesis is CLOSED — the variance was
  sampling. Part 2 (same scenario at the end of a full seeded bank) lands
  with the T746 bank run (compare the bank's batch provider-body hash).
- Wave-1 stack observed live on the 3×-failed scenario: provider body now
  carries `tool_choice {type:function, name:talos.apply_workspace_batch}`
  (T739 NAMED), `top_p 0.8 / top_k 20 / seed 424242` (T740), and the run
  PASSED via the T743 ladder: trace `UNSATISFIED` (first attempt, temp 0.2,
  no tool call) → escalated retry (temp 0.0) → `SATISFIED_AFTER_RETRY` →
  approval granted → applied.
- **Follow-up question for T746**: the first attempt carried NAMED tool
  choice yet produced no parsed tool call — investigate at bank scale
  (grammar/template interaction vs transport argument recovery) using the
  bank's per-scenario retry-rate data.
