# [T827-done-high] Architecture Intelligence Qodana Summary Ordering

Status: done
Priority: high
Date: 2026-06-16
Branch: `v0.9.0-beta-dev`
Candidate version: `0.10.5`
Predecessor: `[T826-done-high] tool-call-execution-stage-characterization`

## Why This Ticket Exists

The standalone architecture-intelligence and wiki evidence gate path reads
`build/reports/talos/qodana-summary.json` before it guarantees that the summary
has been generated. On a clean tree, deleting that generated summary and running
`wikiEvidenceCloseGate --rerun-tasks` fails closed with `SUMMARY_MISSING`.

This is the right failure mode, but the ordering is brittle. The gate should
write the local Qodana summary from existing `.qodana` outputs before the
architecture report reads it. The fix must not run Qodana and must not change
Qodana policy.

## Scope

In scope:

- Make `architectureIntelligenceReport` depend on `writeQodanaSummary`.
- Declare `build/reports/talos/qodana-summary.json` as an input to
  `architectureIntelligenceReport`.
- Relax the architecture contract from the local-only
  `RAW_ARTIFACT_MISSING` snapshot to the healthy set
  `{RAW_ARTIFACT_MISSING, PRESENT}`.
- Fail the contract on `SUMMARY_MISSING`, `MALFORMED`, or `NOT_APPLICABLE`.

Out of scope:

- No Qodana execution.
- No Qodana configuration change.
- No `talosQualitySummaries`, `writeQualityMarkdownReports`, or
  `qodanaNativeFreshLocal` dependency on the wiki evidence path.
- No production `src/main` changes.
- No candidate recut.
- No `SetupCmd.java` edits.

## Acceptance Criteria

- Deleting `build/reports/talos/qodana-summary.json` and running
  `wikiEvidenceCloseGate --rerun-tasks` recreates the summary.
- The generated architecture overlay reports Qodana `rawArtifactStatus` as
  `RAW_ARTIFACT_MISSING` or `PRESENT`, not `SUMMARY_MISSING`.
- The architecture contract test fails closed for missing or malformed summary
  evidence.
- Full `check` passes.
- `site/` remains untouched and unstaged.

## Completion Evidence

- Implementation commit: `584f46973654032cd9569171012eaa97c4a4cbad`.
- `architectureIntelligenceReport` now depends on `writeQodanaSummary` and
  declares `build/reports/talos/qodana-summary.json` as an input.
- The architecture contract accepts only healthy Qodana raw artifact states:
  `RAW_ARTIFACT_MISSING` or `PRESENT`.
- Clean-summary proof passed: deleting `qodana-summary.json` and running
  `wikiEvidenceCloseGate --rerun-tasks` recreated the summary and generated
  overlay status `RAW_ARTIFACT_MISSING`.
- Full `check` passed before closeout.

## T828 Preview

After T827 is closed, T828 may start the first production decomposition of
`ToolCallExecutionStage`. The planned first seam is the pre-execution guard
chain behind the stable public `execute(...)` / `IterationOutcome` surface.

T828 must preserve public API shape, result-message shape, approval/trace/ledger
ordering, mutation accounting, failure accounting, and edit-repair accounting.
