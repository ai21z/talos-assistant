# [T342-done-medium] Remove Score Threshold Reranker Runtime Log Policy Edge

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T342`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T334-T340] architecture hygiene ratchet baseline and scanner`

## Evidence Summary

- Source: post-merge architecture burn-down request after T341 CI and T334-T340
  integration.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T342`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: removed one `core-no-runtime` baseline edge by replacing a
  core reranker debug log's runtime policy formatter dependency with a
  content-free diagnostic.
- Verification status: RED/GREEN ownership test, focused reranker tests,
  redaction inventory update, architecture scanner, and full `check` passed.

## Problem

`ScoreThresholdReranker` lives in `dev.talos.core.rerank`, but its debug logging
imported `dev.talos.runtime.policy.SafeLogFormatter` only to print the path of a
dropped retrieval candidate.

That created a core-to-runtime dependency for a nonessential debug detail. The
class owns score normalization, thresholding, and result capping; it should not
depend on runtime policy formatting for those behaviors.

## Goal

Remove the `ScoreThresholdReranker -> SafeLogFormatter` boundary edge without
changing reranking behavior or moving runtime policy classes.

## Non-Goals

- No `SafeLogFormatter` package move.
- No `ProtectedContentPolicy` package move.
- No reranking threshold, sorting, normalization, or capping change.
- No retrieval pipeline behavior change.
- No baseline growth.
- No broad logging-policy redesign.

## Implementation Summary

- Added an ownership regression test proving `ScoreThresholdReranker` does not
  reference `dev.talos.runtime.policy.SafeLogFormatter` in source or in the
  architecture baseline.
- Removed the `SafeLogFormatter` import from `ScoreThresholdReranker`.
- Replaced the dropped-candidate debug log with a content-free message that
  reports only score and threshold.
- Updated the redaction source-inventory test so this call site is treated as
  safe because it no longer logs the candidate path at all.
- Removed the matching baseline entry from
  `config/architecture-boundary-baseline.txt`.

## Architecture Metadata

Capability:

- Retrieval reranking and context-quality filtering.

Operation(s):

- Static ownership boundary cleanup.

Owning package/class:

- `dev.talos.core.rerank.ScoreThresholdReranker`.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low. The only runtime behavior changed is one debug log emitted
  when a retrieval candidate is dropped below the score threshold.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: focused source ownership test plus the real repository
  architecture scanner.
- Verification profile: focused reranker tests, architecture validation, diff
  checks, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: remove one core-rerank runtime-policy logging edge.
- Forbidden: move runtime policy classes or change reranking semantics.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.core.rerank.ScoreThresholdRerankerTest.does_not_depend_on_runtime_log_policy" --no-daemon
```

Result: failed because `ScoreThresholdReranker` and the architecture baseline
still referenced `dev.talos.runtime.policy.SafeLogFormatter`.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.core.rerank.ScoreThresholdRerankerTest.does_not_depend_on_runtime_log_policy" --no-daemon
```

Result: passed after removing the runtime-policy formatter import, replacing
the dropped-candidate debug message with a content-free diagnostic, and
removing the baseline entry.

Focused behavior coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.core.rerank.ScoreThresholdRerankerTest" --no-daemon
```

Result: passed.

Redaction inventory coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest.high_risk_user_controlled_log_values_are_safely_handled" --no-daemon
```

Result: passed after the inventory assertion was updated to require the
content-free reranker debug log and forbid the old path-bearing variants.

Architecture scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed with `58` current and baselined forbidden references, `0` new
violations, and `0` stale entries.

Full check:

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed.

## Acceptance Criteria

- `ScoreThresholdReranker` no longer references
  `dev.talos.runtime.policy.SafeLogFormatter`.
- The matching baseline entry is removed.
- `validateArchitectureBoundaries` passes with no new or stale violations.
- Focused reranker behavior tests pass.
- The redaction source inventory accepts the content-free reranker debug log.
- Full `check` passes.
- No generated audit artifacts are committed.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- The affected debug log no longer includes the dropped candidate path. This is
  intentional because path content is not needed to prove reranker behavior and
  should not create a core-to-runtime policy dependency.
- Other `SafeLogFormatter` baseline edges remain. They should continue to be
  evaluated one at a time.

## Known Follow-Ups

- Continue burn-down against the remaining baseline using one-edge tickets.
- Reconsider `SafeLogFormatter` ownership only after deciding where
  `ProtectedContentPolicy` belongs.
