# T753 - Local Qodana Refresh And Baseline

Status: open
Severity: medium
Release gate: yes - 0.10.2 packet should not cite a stale-provenance scan
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

The only Qodana evidence is a self-confessed stale scan: provenance records
`v0.9.0-beta-dev @ afde6472` while releases are cut elsewhere, and
`qodana-summary.json` honestly reports `stale-qodana-provenance` /
`revision-mismatch`. 155 HIGH findings are untriaged, dominated by two noise
families. The 0.10.1 packet disclosed the staleness (owner-approved); the
0.10.2 packet should cite a current scan instead.

## Evidence Analysis

- `qodana-summary.json` (regenerated 2026-06-10): `summaryStatus:
  stale-qodana-provenance`, `revisionStatus: revision-mismatch`,
  `branchStatus: branch-mismatch`; linter QDJVM 253.31821; 159 issues
  (155 HIGH warning, 4 MODERATE note).
- Noise families measured from the SARIF: 51× RegExpUnnecessaryNonCapturingGroup
  (44 in `runtime/MutationIntent.java` — stylistic), 32× AutoCloseableResource
  (28 = intentional shared-LlmClient lifecycle pattern).
- Fully local tooling verified: `qodanaLocal` (build.gradle.kts:1189-1209) =
  `docker run jetbrains/qodana-jvm-community:2026.1` with volume mounts to
  `.qodana/` and persistent cache volumes; `qodana.yaml` uses the free
  community linter, profile `qodana.starter`, **no cloud token anywhere** —
  satisfies the owner's local-only constraint.
- `writeQodanaSummary` is fail-soft and re-reads `.qodana/` outputs; running
  it post-scan refreshes `build/reports/talos/qodana-summary.json` with
  current provenance.

## Architectural Hypothesis

n/a — quality-evidence refresh ticket.

## Architecture Metadata

Capability: static-analysis evidence
Operation(s): n/a (local Docker scan + config baseline)
Owning package/class: `qodana.yaml`, `.qodana/` outputs,
`build/reports/talos/qodana-summary.json`
New or changed tools: none
Risk, approval, and protected paths: scan excludes build/.qodana/local/.gradle
already (qodana.yaml:13-19)
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: n/a
Refactor scope: qodana.yaml only (+ regenerated reports)

## Required Behavior

1. Run AFTER T752 (fix first, then baseline only residual noise — reverse
   order churns qodana.yaml).
2. `.\gradlew.bat qodanaLocal` on the post-T752 head (Docker must be running;
   first run pulls the image).
3. Triage the fresh results into three buckets: real defects (ticket or fix),
   accepted-pattern suppressions, noise families. Add commented suppressions/
   excludes to `qodana.yaml` for the two measured noise families
   (RegExpUnnecessaryNonCapturingGroup scoped to MutationIntent; shared
   LlmClient AutoCloseableResource pattern) — comments must cite this ticket.
4. `.\gradlew.bat writeQodanaSummary` (or talosQualitySummaries) — verify the
   refreshed summary reports current branch/SHA with no revision-mismatch.
5. Record the triage table (rule → count → bucket → action) in this ticket's
   completion evidence.

## Non-Goals

- No Qodana Cloud, no tokens, no API calls (owner constraint).
- No mass suppression beyond the two measured noise families without triage.

## Tests

- n/a (evidence task); the refreshed summary JSON is the artifact.

## Acceptance Criteria

- Fresh `.qodana` results from the current head; qodana-summary shows matching
  provenance (`revisionStatus: match` or equivalent).
- T752's three findings absent; triage table recorded.
- 0.10.2 candidate packet (wave close) cites the fresh scan.
- CHANGELOG `## [Unreleased]` gains a T753 entry.

## 2026-06-11 completion evidence

- Docker mode (`qodanaLocal`) failed twice with the documented Windows
  Gradle-import failure class (`Could not create service of type FileHasher:
  java.io.IOException: Input/output error` in `.qodana/log/gradle-import.log`;
  the IDE coroutine crashes were secondary noise). The runbook's native
  fallback applies (work-test-cycle-setup.md:166, step-by-step:256). The
  owner ran `.\gradlew.bat qodanaNativeFreshLocal --no-daemon` successfully
  on HEAD `b6f2641f`.
- `writeQodanaSummary` regenerated: `summaryStatus:
  qodana-results-match-current-candidate`, provenance branch
  `codex/wave1-stability-and-cycle` rev `b6f2641f`,
  `revisionStatus: matches-current-revision` — staleness eliminated.
- Triage table (169 findings, 0 critical, 165 HIGH-warning / 4 MODERATE):

  | Rule | Count | Bucket | Action |
  |---|---|---|---|
  | ConstantValue | 55 | dead-branch signal | feed Wave-6 mutation-testing work; no suppression |
  | RegExpUnnecessaryNonCapturingGroup | 51 | noise (44 in MutationIntent) | baselined in qodana.yaml, scoped to MutationIntent |
  | AutoCloseableResource | 37 | accepted shared-LlmClient lifecycle | baselined in qodana.yaml (real lifecycle defect fixed in T752) |
  | DataFlowIssue | 6 | style (meaningless min/max, already-assigned, too-complex) | recorded; no NPE candidates remain (T752 sites clean) |
  | tail (CollectionAddAll 6, UnusedAssignment 3, others 1-2) | 14 | minor style | recorded for opportunistic cleanup |

- The three T752 sites (ContextItem, MutationTargetReadbackVerifier,
  ProcessCommandRunner) no longer appear in the fresh SARIF.
- Suppression validation note: the qodana.yaml baselines take effect on the
  next scan; rationale comments cite this ticket.
