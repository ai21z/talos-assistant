# T748 - Ticket Hygiene Test And Aging Report

Status: done - completed in wave 1; see completion evidence section
Severity: medium
Release gate: no (cycle-integrity hardening)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

Ticket metadata drifts because nothing validates it: T284's acceptance
criterion pointed at a dead filename for weeks, six tickets carried stale
`Branch: v0.9.0-beta-dev` headers through three branches, and T312's evidence
paths were mistyped (`-capability/gptoss/` vs `-capability/artifacts-gptoss/`).
Separately, nothing surfaces queue age - T280 sat open since May with no aging
signal, and open/ mixes true beta blockers with deferred-beyond-beta scope.

## Evidence Analysis

- Drift instances: all found and fixed during the 2026-06-10 evidence-repair
  pass (commits `953bf4eb`, `b9386ab2`) - the defect class is proven, the
  prevention is missing.
- Corpus measured: 716 ticket files (19 open, 697 done). **86 filenames** fail
  the strict `[Txx-(open|done)-(high|medium|low)] slug.md` pattern (~70 legacy
  unbracketed `talos-*.md`, 15 `p0`, 10 `medium-high`, 1 `low-medium`). Body
  variance: T109 `Status: Done`/`Priority: High`; T610 has no Priority line.
- Safe-to-enforce-repo-wide TODAY (zero current violations, verified):
  (a) directory ↔ filename status-token consistency for bracketed files;
  (b) ticket-ID uniqueness across open/ + done/.
- Strict rules are only safe with a grandfather: numeric ID threshold
  (**Txx ≥ 739**) - monotonic IDs make it self-documenting; matches the
  repo's ratchet philosophy (new work can't regress; legacy untouched) better
  than an 86-entry baseline file.
- House docs-test pattern: `src/test/java/dev/talos/docs/ReadmePrivacyCopyTest.java`
  (repo-relative `Path.of`, `Files.readString`, `assertTrue(cond, payload)`).

## Architectural Hypothesis

Tickets are evidence artifacts; they deserve the same two-sided ratchet
treatment as architecture boundaries - deterministic JUnit validation in the
normal test lane, plus a cheap visibility script for queue health.

## Architecture Metadata

Capability: work-cycle documentation integrity
Operation(s): n/a (test + script)
Owning package/class: `dev.talos.docs.TicketHygieneTest` (new),
`scripts/ticket-aging.ps1` (new)
New or changed tools: none
Risk, approval, and protected paths: n/a
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: n/a
Refactor scope: new test + new script only; no legacy ticket edits

## Required Behavior

TicketHygieneTest (runs in the normal `test` lane):
1. Repo-wide: bracketed filenames in open/ carry `-open-`, in done/ carry
   `-done-`; no duplicate ticket IDs across both directories.
2. Strict (ID ≥ 739 only): filename matches
   `\[T\d+-(open|done)-(high|medium|low)\]` with a non-empty slug; body
   contains a `Status:` line whose open/done token matches the filename.
3. Failure messages name the offending file and rule.

ticket-aging.ps1: lists open tickets sorted by LastWriteTime with ID, age in
days, priority token, and first Status line; `-Stale <days>` filter;
read-only.

## Non-Goals

- No editing/renaming of the 86 legacy files.
- No evidence-path existence validation in v1 (machine-local `local/` paths
  make it environment-dependent; revisit later).

## Tests

- TicketHygieneTest itself (it IS the test) - plus fixture-style negative
  verification during development (temp file violating a rule → assertion
  fires; removed before commit).
- `pwsh scripts/ticket-aging.ps1` produces the listing; `-Stale 14` filters.

## Acceptance Criteria

- `./gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --no-daemon`
  green against the current corpus (proving the grandfather threshold holds).
- Wave-1 tickets (T739-T753) all pass the strict rules.
- Aging script output sane on the current queue.
- CHANGELOG `## [Unreleased]` gains a T748 entry.

## 2026-06-11 completion evidence

- `TicketHygieneTest` landed with three rules: bracketed status-token vs
  directory (repo-wide), ticket-ID uniqueness across open/+done/ (repo-wide),
  strict filename vocabulary + body `Status:` line for IDs >= 739
  (grandfather threshold; the 80+ legacy filename variants untouched).
  Green against the full 716-file corpus.
- `scripts/ticket-aging.ps1` landed; first run surfaced five high-priority
  tickets 21 days stale (T274, T276, T281, T283, T286) - exactly the queue
  signal the cycle lacked.
