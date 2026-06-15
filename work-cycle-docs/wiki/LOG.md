---
wiki_schema: talos.wikiPage.v1
title: "Talos Wiki Log"
kind: log
status: active
last_verified_commit: "8fa6a631a65952dce8d063c088895e332d06b2b7"
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
    ref: "work-cycle-docs/tickets/done/[T812-done-high] assistant-turn-executor-model-dispatch-characterization.md"
    selector: "Model dispatch characterization closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T813-done-high] assistant-turn-executor-model-dispatch-extraction.md"
    selector: "Model dispatch extraction closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T814-done-high] assistant-turn-executor-tool-loop-outcome-characterization.md"
    selector: "Tool-loop outcome characterization closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T815-done-high] assistant-turn-executor-tool-loop-outcome-extraction.md"
    selector: "Tool-loop outcome resolver extraction closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T816-done-high] assistant-turn-executor-no-tool-outcome-characterization.md"
    selector: "No-tool outcome characterization closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T817-done-high] assistant-turn-executor-no-tool-outcome-extraction.md"
    selector: "No-tool outcome resolver extraction closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T818-done-high] assistant-turn-executor-prompt-instruction-adapter-thinning.md"
    selector: "Prompt-instruction adapter thinning closeout"
  - type: repo_file
    ref: "work-cycle-docs/reports/t811-assistant-turn-executor-lifecycle-characterization.md"
    selector: "Lifecycle Ownership Map"
min_confidence: DETERMINISTIC_STATIC
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 0
  DETERMINISTIC_STATIC: 10
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

## [2026-06-14] ledger | Close T812

- Moved T812 to the done ticket ledger after the model-dispatch
  characterization tests and green gates.
- Recorded that T812 made no production extraction and left
  `TurnModelDispatcher` for T813.
- Set the next Wave 5 move to T813, the first production model-dispatch
  extraction guarded by the T812 tests.

## [2026-06-14] plan | Open T813 model dispatch extraction

- Added T813 as the first production model-dispatch extraction ticket after
  T812 characterization.
- Locked the move/stay boundary: raw provider dispatch and request controls can
  move to `TurnModelDispatcher`; retry decisions, trace begin/set/clear,
  tool-loop/no-tool outcome resolution, answer shaping, and truthfulness repair
  stay in `AssistantTurnExecutor`.
- Recorded the T812 characterization tests as the guard set for the extraction.

## [2026-06-14] extraction | Close T813 model dispatch

- Extracted model-dispatch mechanics from `AssistantTurnExecutor` into
  package-private `TurnModelDispatcher`.
- Preserved executor ownership for retry decisions, trace begin/set/clear,
  tool-loop/no-tool outcome resolution, answer shaping, and `TurnOutput`
  assembly.
- Rebound the four ordinary retry dispatch callbacks through synchronous
  no-timeout dispatcher calls with retry-message plan resolution kept
  executor-side.
- Rebound missing-mutation escalated retry through dispatcher-owned
  zero-temperature dispatch.
- Regenerated architecture intelligence: `AssistantTurnExecutor` remains the
  first Wave 5 candidate on this commit with priority index `384`.

## [2026-06-14] characterization | Open T814 tool-loop outcome

- Added T814 as a characterization-only Wave 5 ticket for the
  `AssistantTurnExecutor` post-tool-loop outcome boundary.
- Pinned the proposed T815 owner as package-private
  `AssistantToolLoopOutcomeResolver` while keeping production extraction out of
  T814.
- Recorded that `ToolCallLoop.LoopResult` and `ToolOutcome` compatibility
  surfaces remain outside this extraction boundary.

## [2026-06-15] ledger | Close T814 tool-loop outcome

- Moved T814 to the done ticket ledger after the tool-loop outcome
  characterization tests and green gates.
- Recorded that T814 made no production source changes.
- Set the next Wave 5 move to T815, the package-private
  `AssistantToolLoopOutcomeResolver` extraction guarded by T814 coverage.

## [2026-06-15] extraction | Open T815 tool-loop outcome

- Added T815 as the first production extraction of the post-tool-loop outcome
  boundary after T814 characterization.
- Extracted retry/evidence-recovery/summary ordering into package-private
  `AssistantToolLoopOutcomeResolver`.
- Kept no-tool outcome handling, trace begin/set/clear, branch selection,
  `ToolCallLoop.LoopResult`, `ToolOutcome`, and `TurnOutput` assembly in
  `AssistantTurnExecutor`.

## [2026-06-15] ledger | Close T815 tool-loop outcome

- Moved T815 to the done ticket ledger after green focused, broad, `check`,
  and wiki evidence gates.
- Recorded that T815 extracted `AssistantToolLoopOutcomeResolver` while
  keeping no-tool outcome handling, trace lifecycle, branch selection,
  `ToolCallLoop.LoopResult`, `ToolOutcome`, and `TurnOutput` assembly in
  `AssistantTurnExecutor`.
- Set the next Wave 5 move to T816, a characterization-only no-tool outcome
  boundary ticket.

## [2026-06-15] characterization | Open T816 no-tool outcome

- Added T816 as a characterization-only ticket for
  `AssistantTurnExecutor.resolveNoToolAnswer(...)`.
- Pinned the future T817 owner as package-private
  `AssistantNoToolOutcomeResolver`.
- Kept production extraction out of T816.

## [2026-06-15] ledger | Close T816 no-tool outcome

- Moved T816 to the done ticket ledger after green focused, broad, `check`,
  and wiki evidence gates.
- Recorded that T816 added characterization-only coverage for malformed
  protocol/debris retry, no-tool missing-mutation retry, read-evidence
  handoff, read-only inspection retry, and stream-sink buffering.
- Set the next Wave 5 move to T817, the package-private
  `AssistantNoToolOutcomeResolver` extraction.

## [2026-06-15] extraction | Open T817 no-tool outcome

- Added T817 as the production extraction ticket for the no-tool outcome
  boundary after T816 characterization.
- Extracted no-tool retry/handoff/inspection ordering into package-private
  `AssistantNoToolOutcomeResolver`.
- Kept answer shaping, trace begin/set/clear, branch selection, the tool-loop
  outcome path, and `TurnOutput` assembly in `AssistantTurnExecutor`.

## [2026-06-15] ledger | Close T817 no-tool outcome

- Moved T817 to the done ticket ledger after green focused, broad, `check`,
  and wiki evidence gates.
- Recorded that T817 extracted `AssistantNoToolOutcomeResolver` while keeping
  shaping, trace lifecycle, branch selection, the tool-loop outcome path, and
  `TurnOutput` assembly in `AssistantTurnExecutor`.
- Set the next Wave 5 move to adapter thinning for remaining
  `AssistantTurnExecutor.inject*` compatibility delegates.

## [2026-06-15] extraction | Open T818 prompt-instruction adapter thinning

- Added T818 as the adapter-thinning ticket for the remaining
  `AssistantTurnExecutor.inject*` compatibility surface.
- Moved prompt-instruction ownership toward
  `runtime.policy.CurrentTurnPromptInstructions`.
- Kept structural SCC/cycle work out of T818.

## [2026-06-15] ledger | Close T818 prompt-instruction adapter thinning

- Moved T818 to the done ticket ledger after green focused, broad, `check`,
  architecture boundary, and wiki evidence gates.
- Recorded production thinning at `ef97bda0` and test ownership hardening at
  `8fa6a631`.
- Set the next Wave 5 move to T819 report-only `core-tools-cycle-edge-scoping`.
