---
wiki_schema: talos.wikiPage.v1
title: "Talos Wiki Log"
kind: log
status: active
last_verified_commit: "b3ed424b4c17567b64842ffa38c14f2ed4103d82"
evidence_inputs:
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T808-done-high] living-evidence-wiki-discipline.md"
    selector: "Initial wiki spine"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T809-done-high] wiki-evidence-liveness-lint.md"
    selector: "Initial evidence-liveness lint"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T810-done-high] living-wiki-operating-loop-and-close-gate.md"
    selector: "Operating loop and close gate"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T811-done-high] assistant-turn-executor-lifecycle-ownership-characterization.md"
    selector: "Wave 5 first ticket closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T812-open-high] assistant-turn-executor-model-dispatch-characterization.md"
    selector: "Model dispatch characterization"
  - type: repo_file
    ref: "work-cycle-docs/reports/t811-assistant-turn-executor-lifecycle-characterization.md"
    selector: "Lifecycle Ownership Map"
min_confidence: DETERMINISTIC_STATIC
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 0
  DETERMINISTIC_STATIC: 5
  DETERMINISTIC_GENERATED: 1
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

## [2026-06-13] ledger | Close T807-T810 and open T811

- Moved T807, T808, T809, and T810 to the done ticket ledger after owner
  review and green gates.
- Added T811 as the first Wave 5 ticket for
  `cli.modes.AssistantTurnExecutor` lifecycle ownership characterization.
- Updated the current-state page so the active ticket is T811 and the next
  move is characterization before extraction.

## [2026-06-13] characterization | T811 lifecycle baseline

- Marked T811 in progress and added the committed lifecycle characterization
  report for `cli.modes.AssistantTurnExecutor`.
- Updated wiki links from the open ticket filename to the in-progress ticket
  filename.
- Kept runtime refactoring out of this pass; the report is a baseline for the
  next behavior-preserving extraction.

## [2026-06-14] extraction | T811 turn preparation

- Moved ordered turn-preparation and prompt-audit setup from
  `AssistantTurnExecutor` into package-private `AssistantTurnPreparation`.
- Preserved `AssistantTurnExecutor.execute(...)` as the public entrypoint and
  kept model dispatch, tool-loop outcome, no-tool outcome, retry, and answer
  shaping in the executor.
- Regenerated architecture intelligence: `AssistantTurnExecutor` remains the
  first Wave 5 candidate on this commit with priority index `401`, and
  `AssistantTurnPreparation` appears as point-in-time evidence with priority
  index `136`.

## [2026-06-14] ledger | Close T811

- Moved T811 to the done ticket ledger after the turn-preparation extraction
  commit and green characterization evidence.
- Recorded that T811 did not complete Wave 5 and did not extract model
  dispatch.
- Set the next Wave 5 move to a separate characterization-only T812 model
  dispatch boundary ticket.

## [2026-06-14] characterization | Open T812 model dispatch

- Added T812 as a characterization-only Wave 5 ticket for the
  `AssistantTurnExecutor` model-dispatch boundary.
- Kept production extraction deferred to T813 after provider controls,
  zero-temperature retry dispatch, streaming/buffered answer shape, and
  tool-only streaming completion ordering are pinned by tests.
- Recorded that provider-body and prompt-debug capture remain downstream in
  `LlmClient`, `OllamaChatClient`, and `CompatChatClient`.
