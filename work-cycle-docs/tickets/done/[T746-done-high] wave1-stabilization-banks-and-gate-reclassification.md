# T746 - Wave-1 Stabilization Banks And Gate Reclassification

Status: done - completed in wave 1; see completion evidence section
Severity: high
Release gate: yes - produces the evidence that re-opens the T280/T284/T312 closure path
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

After T739-T745 land, the corrected constraint envelope must be proven live:
the 0.10.1 packet's "model-owned" classification of the Qwen full-bank
failures is incomplete (byte-level provider-body analysis shows a runtime
constraint-coverage gap as the dominant cause), and the gates currently rest
on that classification. The wave needs full two-model stabilization banks plus
an honest reclassification recorded as new dated evidence.

## Evidence Analysis

- Prior verdicts: three Qwen full-bank fail-closed runs (scenarios 31/25/31);
  GPT-OSS full bank PASS; focused reruns pass — all anchored in
  `work-cycle-docs/reports/current-0.10.1-release-packet-20260610-090049-results.md`
  (final classification section) and the r3 root
  `local/manual-testing/current-0.10.1-qwen-syncbank-r3-20260610-210541/artifacts`.
- Root-cause evidence for reclassification: ProviderRequestControlPolicy gap
  (T739 evidence), absent sampling with byte-identical bodies (T740 evidence),
  double-encoded batch schema (T744), repair-ladder skips (T743) — full
  citations in `work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md`.
- Known runner gap (recorded in done T306): when a bank aborts, the runner's
  own post-bank canary scan is bypassed — any aborted root must be scanned
  manually with `checkRuntimeArtifactCanaries`.
- Observed runtimes: full live bank ≈ 1-3 minutes (r3: 30 scenarios + failure
  in 62s; GPT-OSS 0.10.1 bank comparable) — cheap to run both models.

## Architectural Hypothesis

n/a — this is an evidence/audit ticket, not a code change.

## Architecture Metadata

Capability: live audit execution + release-gate documentation
Operation(s): full synchronized approval banks, both models
Owning package/class: harness invocation + work-cycle-docs reports/tickets
New or changed tools: none
Risk, approval, and protected paths: scripted approvals inside the harness
(synchronized lane); no user-home contact (packet configs + isolated roots)
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: full scenario bundles + bank summaries + canary scans
Refactor scope: docs only (report + T280/T284/T312 updates)

## Required Behavior

1. Run full live banks to fresh timestamped roots under
   `local/manual-testing/wave1-stabilization-<ts>/`:
   - Qwen (`qwen-config.yaml` from the 0.10.0 packet configs, as before);
   - GPT-OSS (regression check — sampling/tool-choice changes affect it too).
2. Canary-scan every produced root (manually for any aborted bank).
3. Evidence grade: **stabilization** (mid-wave tree) — explicitly NOT release
   evidence; the release-grade rerun happens from the committed 0.10.2
   candidate at wave close.
4. Update the 0.10.1 packet report + T280/T284/T312 with a dated
   reclassification: "primarily runtime constraint-coverage gap
   (tool_choice/sampling/schema/repair-ladder); model sensitivity as trigger;
   GPT-OSS tolerant" — recorded as NEW evidence, no history rewriting.
5. If a bank still fails closed: classify honestly with the T745 A/B data;
   gates stay open; no wording softening.

## Non-Goals

- No release/beta claims from these runs (dirty-tree downgrade rule).
- No PTY rerun (0.10.1 PASS stands).

## Tests

- n/a (live evidence); canary scans are the artifact gate.

## Acceptance Criteria

- Both bank summaries on disk; expected: 31/31 PASS-family Qwen and GPT-OSS.
- Canary scans PASS over all produced roots.
- Reclassification recorded in the packet report and the three gate tickets
  with evidence paths; docs commit made.
- CHANGELOG `## [Unreleased]` gains a T746 entry (evidence note).

## 2026-06-11 completion evidence

- Qwen full bank: **31/31 PASS-family, artifact scan PASS** — the first
  complete Qwen bank in four attempts:
  `local/manual-testing/wave1-stabilization-qwen-20260611-005233/artifacts`
  (`workspace-batch-apply-approved` plain PASS; `t325`
  PASS_WITH_READBACK_ONLY_LIMITATION; 5/31 scenarios rescued by the bounded
  T743 ladder — `SATISFIED_AFTER_RETRY` — honestly recorded).
- GPT-OSS full bank: 31/31, artifact scan PASS, **zero rescues** (no
  regression from the wave-1 stack):
  `local/manual-testing/wave1-stabilization-gptoss-20260611-005426/artifacts`.
- Runner self-scans passed for both banks (no aborts, so the known
  post-abort scan gap was not exercised).
- Reclassification + evidence recorded in the packet report addendum and
  T280/T284/T312.
- Open observation carried to wave close: first attempts under NAMED tool
  choice still occasionally produce no parsed call (5/31 Qwen rescues) —
  grammar/template interaction worth a focused look when the 0.10.2 packet
  data lands.
