---
wiki_schema: talos.wikiPage.v1
title: "Talos Wiki Log"
kind: log
status: active
last_verified_commit: "a5a963540e1bf7979d4d31f2ec4f5a30b6a8e87d"
evidence_inputs:
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T808-open-high] living-evidence-wiki-discipline.md"
    selector: "Initial wiki spine"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T809-open-high] wiki-evidence-liveness-lint.md"
    selector: "Initial evidence-liveness lint"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T810-open-high] living-wiki-operating-loop-and-close-gate.md"
    selector: "Operating loop and close gate"
min_confidence: DETERMINISTIC_STATIC
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 0
  DETERMINISTIC_STATIC: 3
  DETERMINISTIC_GENERATED: 0
  OBSERVED_RUNTIME: 0
  GATED: 0
---

# Talos Wiki Log

This log is append-only by convention. T808 structural lint checks the file
exists and has valid frontmatter, but it does not enforce append-only history
mechanically.

## [2026-06-13] ingest | Living evidence wiki spine

- Added the initial wiki entry points, schema page, current-state page, log, and
  living-evidence-wiki concept page.
- Added structural lint as the first wiki discipline gate.
- Deferred evidence-liveness, search tooling, Obsidian conventions, runtime
  memory loading, and `llms.txt`.

## [2026-06-13] lint | Generated-report evidence liveness

- Added T809 as the first evidence-liveness pass for wiki claim blocks.
- Added machine-checkable generated-report claims to `CURRENT-STATE.md`.
- Kept external-source freshness, Markdown table parsing, Qodana fixes, search
  tooling, and runtime wiki loading deferred.

## [2026-06-13] hardening | Volatile identity and confidence semantics

- Removed branch and commit from hard generated-report wiki claims.
- Changed the generated Talos version claim to cross-check `gradle.properties`
  instead of pinning a literal version.
- Defined `min_confidence` as the lowest populated confidence histogram bucket.
- Kept branch, commit, and `last_verified_commit` checks advisory.

## [2026-06-13] gate | Living wiki close gate

- Added T810 to make wiki evidence liveness load-bearing through an explicit
  close/candidate gate rather than the fast `check` loop.
- Added the evidence registry for generated architecture intelligence evidence
  that the wiki close gate can refresh.
- Kept quality summaries and identity-freshness output deferred as registry
  inputs until their producers belong to the same evidence-liveness gate.
- Excluded report-generation and generated-report liveness tests from the
  default `test` task so `check` does not depend on stale ignored build output
  or JUnit class ordering.

## [2026-06-13] refresh | Post-merge current state

- Updated current-state identity to `v0.9.0-beta-dev` at
  `a5a963540e1bf7979d4d31f2ec4f5a30b6a8e87d`.
- Recorded Wave 5 as ready for first-ticket planning after the T807-T810
  batch was committed and fast-forwarded.
