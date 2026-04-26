# Work-Test-Cycle Branch: Applied Review Fixes

This document records the concrete fixes applied on `feature/work-test-cycle`
after the April 2026 adversarial review. It exists so a reviewer can
cross-check what was claimed against what was changed.

## Fixes Applied

### F3 — Persistence path determinism gap (medium)
**File**: `src/e2eTest/java/dev/talos/harness/ScenarioRunner.java`

`ScenarioRunner.runWithPersistence(...)` was constructing a real
`LlmClient(new Config())` at line 193. `MemoryUpdateListener.onTurnComplete`
delegates to `ConversationManager.maybeCompact(llm)`, which calls
`LlmClient.chatFull(...)` for sketch generation — introducing
network-dependent nondeterminism into persistence snapshots.

Replaced with `LlmClient.scripted(List.of(""))`. Compaction now receives
empty scripted output; snapshots are byte-deterministic.

Verified: `PersistenceScenarioPackTest` does not assert on `sketch()` or
`getModel()`, so no additional overload is needed. Scripted-client default
model string is sufficient.

### F4 — Summary-task fail-soft wrapper (low-to-medium)
**File**: `build.gradle.kts`

Introduced `writeSummarySoft(target, summaryName, payloadBuilder)` helper.
Wraps each summary's payload construction. If the builder throws — for
example, on a truncated SARIF or corrupt JUnit XML — the task writes a
fallback JSON with `summaryStatus: summary-generation-failed`,
`errorClass`, and `errorMessage` instead of letting the exception take down
the whole packet.

Applied to all four summary tasks:
- `writeVersionSummary`
- `writeCoverageSummary`
- `writeQodanaSummary`
- `writeE2eSummary`

### F2 — Summary payloads no longer contain wall-clock `generatedAt` (medium)
**File**: `build.gradle.kts`

`generatedAt: Instant.now()` removed from the four summary JSON payloads.
Summaries are now byte-reproducible functions of their declared inputs: two
runs with identical evidence produce identical JSON. Useful for
candidate-to-candidate diffing.

`generatedAtIso()` retained for the jar manifest `Implementation-Vendor`
attribute, which is separate from the evidence-reproducibility contract.

### F1 — Revised (not a bug)
**File**: `build.gradle.kts`

`writeVersionSummary` retains `outputs.upToDateWhen { false }` because its
payload reports `jarTask.state` observed at execution time — that state is
per-invocation and cannot be declared as a Gradle input. Without the
predicate, the first run's `built-in-current-run` status would be cached
and never refresh to `up-to-date-in-current-run` on subsequent invocations.
Added a comment in the build file explaining the necessity so future
maintainers do not "simplify" this away.

My prior review's F1 framing implied the predicate was a defect. After
correction, combined with F2 (removing `Instant.now()`), the remaining
per-invocation variability is legitimate and explicit.

### F6 — Precise directory inputs (low)
**File**: `build.gradle.kts`

`writeCoverageSummary` and `writeE2eSummary` now declare inputs as
`fileTree(dir) { include("TEST-*.xml") }` instead of `inputs.dir(dir)`.
Neighbor files (binary results, IDE temp) no longer invalidate the Gradle
cache.

### F9 — Docs tightening (low)
**File**: `work-cycle-docs/work-test-cycle.md`

Three notes updated:
- Determinism claim is now explicit about scope: `run(...)`, `runStrict(...)`,
  and `runWithPersistence(...)`. Does not claim anything about paths outside
  `ScenarioRunner`.
- "Summary tasks re-run when evidence changes" clarified with the
  content-reproducibility guarantee.
- New bullet documents the fail-soft summary behavior.

## New Tests

### `QodanaSummaryTaskTest.reportsMatchingProvenanceWhenQodanaAgreesWithCurrentGit`
Locks in the positive-match happy path: when Qodana's recorded
branch/revision equal the current git state, `summaryStatus` reports
`qodana-results-match-current-candidate`. Previously, all three existing
tests exercised only negative outcomes (missing, incomplete, unavailable).
The "honest provenance" claim's happy path was untested.

Uses a throwaway `git init` in the fixture to synthesize deterministic
branch/revision values. Requires git on PATH (standard for a Java/Gradle
project CI).

### `QodanaSummaryTaskTest.writesFailSoftPayloadWhenSarifIsMalformed`
Locks in the F4 fail-soft contract: a deliberately corrupt
`qodana.sarif.json` must not propagate an exception. The task must still
write `summaryStatus: summary-generation-failed`, so the packet exists
even under malformed evidence.

## What Was NOT Fixed (Deferred)

- **F5** (Qodana has no task-level ordering guarantee): still documented as
  manual workflow discipline. Automating this would require either a Gradle
  Docker plugin or an mtime heuristic. Out of scope for this branch.
- **F7** (scenario display-name convention has no static enforcement): no
  change. The convention is still human-enforced. Low-severity and
  orthogonal to the summary layer.
- **F8** (test cross-invocation coupling in `VersionSummaryTaskTest`):
  behavior is correct, only clarity concern. Not fixed.

## Net Effect on Claims Audit

After fixes, the three "partially substantiated" claims now upgrade:

| Claim | Before | After |
|---|---|---|
| Harness-backed E2E deterministic | partially | **substantiated** (all three ScenarioRunner entry points) |
| Packet exists even when evidence fails | partially | **substantiated** (fail-soft summaries) |
| Version summary honestly represents jar identity | partially | **substantiated** (`upToDateWhen false` now documented; no phantom timestamps) |

Remaining caveat: F5 (Qodana workflow discipline) keeps the "candidate
packet trustworthiness" claim at *partially substantiated* until the manual
Qodana step is automated or guarded.
