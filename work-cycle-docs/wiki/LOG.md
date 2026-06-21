---
wiki_schema: talos.wikiPage.v1
title: "Talos Wiki Log"
kind: log
status: active
last_verified_commit: "f35b8bc88533152c4c307a70a7b5814eba04c489"
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
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T819-done-high] core-tools-cycle-edge-scoping.md"
    selector: "Core-tools cycle edge scoping closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T820-done-high] context-item-tool-result-adapter-cycle-break.md"
    selector: "ContextItem tool-result adapter cycle break closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T821-done-high] system-prompt-builder-tool-catalog-cycle-break.md"
    selector: "SystemPromptBuilder tool-catalog cycle seam closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T822-done-high] rag-tool-protocol-text-cycle-break.md"
    selector: "RagService tool-protocol text cycle seam closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T823-done-high] tool-call-loop-orchestration-characterization.md"
    selector: "ToolCallLoop orchestration characterization closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T824-open-high] tool-call-loop-engine-extraction.md"
    selector: "ToolCallLoopEngine extraction"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T828-done-high] tool-call-execution-stage-guard-chain-extraction.md"
    selector: "ToolCallExecutionStage guard-chain extraction closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T829-done-high] tool-call-support-boundary-scoping.md"
    selector: "ToolCallSupport boundary scoping closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T830-done-high] tool-call-support-native-call-conversion-extraction.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T831-done-high] tool-call-support-result-formatting-extraction.md"
    selector: "Scope"
  - type: repo_file
    ref: "work-cycle-docs/reports/t819-core-tools-cycle-edge-scoping.md"
    selector: "Generated Package Evidence"
  - type: repo_file
    ref: "work-cycle-docs/reports/t811-assistant-turn-executor-lifecycle-characterization.md"
    selector: "Lifecycle Ownership Map"
  - type: repo_file
    ref: "work-cycle-docs/reports/t823-tool-call-loop-orchestration-characterization.md"
    selector: "ToolCallLoop orchestration boundary"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 5
  DETERMINISTIC_STATIC: 16
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

## [2026-06-15] scoping | Open T819 core-tools cycle edge

- Added T819 as a report-only scoping ticket for the remaining top-level
  `core <-> tools` package cycle.
- Recorded current generated evidence: one SCC `{core, tools}`,
  `core -> tools = 8`, and `tools -> core = 40`.
- Kept production cycle surgery deferred to a later T820 decision.

## [2026-06-15] ledger | Close T819 core-tools cycle edge

- Moved T819 to the done ticket ledger after report-only scoping and green
  wiki evidence gates.
- Recorded that T820 should start at the `ContextItem` tool-result adapter /
  neutral privacy seam.
- Kept `SystemPromptBuilder` and `RagService` cycle seams deferred.

## [2026-06-15] extraction | Open T820 ContextItem adapter seam

- Added T820 as the first production step in the `core -> tools` cycle-break
  arc.
- Introduced the planned direction: keep `ContextItem` as a core model, move
  tool-result conversion into `runtime.toolcall`, and make context privacy
  classification core-owned.
- Kept `SystemPromptBuilder` and `RagService` cycle seams deferred.

## [2026-06-15] extraction | Implement T820 ContextItem adapter seam

- Added `ContextPrivacyClass` as the core-owned context privacy enum.
- Moved tool-result-to-context conversion into package-private
  `ToolResultContextItemAdapter`.
- Regenerated architecture intelligence: `core -> tools` dropped from 8 to 4,
  and the `{core, tools}` SCC remains for the deferred `SystemPromptBuilder`
  and `RagService` seams.

## [2026-06-15] ledger | Close T820 ContextItem adapter seam

- Moved T820 to the done ticket ledger after adding enum parity coverage for
  the adapter's privacy mapping.
- Recorded that `core.context` no longer imports `tools` and
  `ContextItem.fromToolResult(...)` is gone.
- Set the next Wave 5 move to T821, the `SystemPromptBuilder` tool-catalog
  cycle seam.

## [2026-06-15] extraction | Open T821 SystemPromptBuilder tool catalog seam

- Added T821 as the second production step in the `core -> tools` cycle-break
  arc after T820.
- Introduced the planned direction: keep prompt tool rendering in `core.llm`
  but feed it neutral prompt-facing descriptors instead of executable tool
  registry types.
- Kept the remaining `RagService` / `ToolProtocolText` seam deferred.

## [2026-06-15] ledger | Close T821 SystemPromptBuilder tool catalog seam

- Moved T821 to the done ticket ledger after the neutral prompt descriptor
  seam and golden rendering guard were committed.
- Recorded that production `core.llm` no longer imports executable tool
  registry types.
- Set the next Wave 5 move to T822, the final `RagService` /
  `ToolProtocolText` cycle seam.

## [2026-06-15] extraction | Open T822 final core-tools cycle seam

- Added T822 for the final production `core -> tools` dependency:
  `RagService` tool-protocol text cleanup.
- Introduced neutral core owners for tool-name policy and non-executing
  protocol-text cleanup while keeping `tools` compatibility wrappers.
- Local regenerated architecture evidence after implementation shows
  `core -> tools = 0` and no non-trivial top-level package SCCs.

## [2026-06-15] ledger | Close T822 final core-tools cycle seam

- Moved T822 to the done ticket ledger after green focused, broad, `check`,
  architecture, and wiki evidence gates.
- Recorded that `core -> tools = 0` and no non-trivial top-level package SCCs
  remain in regenerated architecture evidence.
- Set the next Wave 5 move to T823, a characterization-only `ToolCallLoop`
  orchestration boundary ticket before any T824 extraction.

## [2026-06-15] characterization | Open T823 ToolCallLoop orchestration

- Added T823 as a characterization-only ticket for `runtime.ToolCallLoop`
  orchestration after the core/tools cycle arc was closed.
- Pinned no-tool, text tool-call, native tool-call, iteration-limit, and
  report-boundary behavior before any `ToolCallLoopEngine` extraction.
- Kept production extraction deferred to T824.

## [2026-06-15] ledger | Close T823 ToolCallLoop orchestration

- Moved T823 to the done ticket ledger after green focused, broad, `check`,
  and wiki evidence gates.
- Corrected the future engine owner to package-private
  `dev.talos.runtime.ToolCallLoopEngine` so Java package-private access remains
  valid without broadening `ToolLoopFinalAnswerFinalizer`.
- Set the next Wave 5 move to T824, a behavior-preserving
  `ToolCallLoopEngine` extraction.

## [2026-06-15] extraction | Open T824 ToolCallLoopEngine

- Added T824 as the behavior-preserving extraction ticket after T823
  characterization.
- Extracted `ToolCallLoop` orchestration into package-private
  `dev.talos.runtime.ToolCallLoopEngine`.
- Kept the public `ToolCallLoop` facade, `LoopResult`, `ToolOutcome`,
  constructors, `run(...)` overloads, and static helper delegates stable.

## [2026-06-15] ledger | Close T824 ToolCallLoopEngine

- Moved T824 to the done ticket ledger after focused `ToolCallLoop` guards,
  full `check`, and wiki evidence gates passed.
- Recorded implementation commit `2d4a9611ad7357cb50f080d5b9c468a5a824f06e`
  and the package-private `dev.talos.runtime.ToolCallLoopEngine` boundary.
- Set the next Wave 5 move to T825, a scoping/characterization ticket for the
  remaining `runtime.toolcall` internals before any T826 extraction.

## [2026-06-15] scoping | Open T825 tool-loop internals boundary

- Added T825 as scoping/characterization for remaining `runtime.toolcall`
  internals after the T824 engine extraction.
- Recorded current `INFERRED_REVIEW` hotspot evidence for `ToolCallLoop`,
  `LoopState`, `ToolCallSupport`, and `ToolCallExecutionStage`.
- Deferred `TurnProcessor`, `TaskContract`, and `ExecutionOutcome` while T825
  chooses a single T826 production seam.

## [2026-06-16] ledger | Close T825 tool-loop internals boundary

- Moved T825 to the done ticket ledger after scoping the remaining
  `runtime.toolcall` internals.
- Recorded regenerated architecture evidence at
  `482fccc7b624fd0be77a439d3b61f375f070d24c`.
- Selected T826 `ToolCallExecutionStage` characterization as the next step
  before any production decomposition.

## [2026-06-16] characterization | Open T826 ToolCallExecutionStage

- Added T826 as characterization-only for direct
  `ToolCallExecutionStage.execute(...)` behavior.
- Pinned text/native result-message shape, approval denial, private-document
  pre-execution blocking, context-ledger decisions, successful execution, and
  failed edit accounting.
- Kept production decomposition deferred to T827.

## [2026-06-16] ledger | Close T826 ToolCallExecutionStage

- Moved T826 to the done ticket ledger after direct stage-level
  characterization, focused runtime guards, clean `check`, and wiki evidence
  gates passed.
- Recorded implementation commit `7e3bb9c0e59e726e7a4b809df94b02249c859dc3`.
- Reserved T827 for architecture-intelligence Qodana-summary evidence-order
  hardening before any T828 production decomposition.

## [2026-06-16] tooling | Open T827 Qodana summary ordering

- Added T827 to make architecture-intelligence and wiki evidence gates generate
  `qodana-summary.json` before report validation reads it.
- Kept Qodana execution and Qodana configuration out of scope.
- Deferred the first production `ToolCallExecutionStage` decomposition to T828.

## [2026-06-16] ledger | Close T827 Qodana summary ordering

- Moved T827 to the done ticket ledger after clean-summary gate proof,
  architecture evidence liveness, and full `check` passed.
- Recorded implementation commit `584f46973654032cd9569171012eaa97c4a4cbad`.
- Set the next move to T828, the first production
  `ToolCallExecutionStage` decomposition.

## [2026-06-16] extraction | Open T828 ToolCallExecutionStage guard chain

- Added T828 as the first production decomposition of
  `ToolCallExecutionStage` after T826 characterization and T827 evidence-order
  hardening.
- Extracted the pre-execution guard chain into package-private
  `ToolCallPreExecutionGuardChain`.
- Kept the public `ToolCallExecutionStage.execute(...)` and
  `IterationOutcome` surface stable.

## [2026-06-17] ledger | Close T828 ToolCallExecutionStage guard chain

- Moved T828 to the done ticket ledger after green focused/security suites,
  full `check`, architecture evidence liveness, and wiki evidence gates.
- Recorded implementation commit
  `4d45b3ed54b50bdf75ceb457b298a572a0783d7a`.
- Set the next Wave 5 move to T829, a scoping/characterization ticket for the
  broad `ToolCallSupport` helper surface before any T830 extraction.

## [2026-06-17] scoping | Open T829 ToolCallSupport boundary

- Added T829 as characterization/scoping for the broad `ToolCallSupport`
  helper surface after the T828 guard-chain extraction.
- Recorded current generated evidence for `ToolCallSupport`, `LoopState`,
  `ToolCallExecutionStage`, and `ExecutionOutcome`.
- Kept native-call conversion, result formatting, retry/request extraction,
  path/call repair, and compaction as hypotheses until T829 is reviewed.

## [2026-06-17] ledger | Close T829 ToolCallSupport boundary

- Moved T829 to the done ticket ledger after focused characterization, full
  `check`, and wiki evidence gates passed.
- Recorded architecture evidence anchored to
  `4104a90c6a9736997b13aa8736a3be2db68c7a17`.
- Selected T830 native-call conversion as the first production
  `ToolCallSupport` extraction seam.

## [2026-06-17] extraction | Open T830 native-call conversion

- Added T830 as the first production `ToolCallSupport` extraction after T829
  scoping.
- Scoped the move to native-call conversion and native argument rendering into
  package-private `NativeToolCallConverter`.
- Kept result formatting, retry/request extraction, path/call repair,
  compaction, stages, and trust-surface redaction deferred.

## [2026-06-17] ledger | Close T830 native-call conversion

- Moved T830 to the done ticket ledger after focused native/tool-loop suites,
  full `check`, source hygiene, architecture evidence liveness, and wiki
  evidence gates passed.
- Recorded implementation commit
  `496799a46ca131a0d8164e49e2a6be130efe6e69`.
- Set the next move to T831 result-formatting extraction while keeping
  `ToolCallSupport` and `ToolCallLoop` delegates stable.

## [2026-06-17] extraction | Open T831 result formatting

- Added T831 as the next narrow `ToolCallSupport` extraction after T830.
- Scoped the move to prompt-visible result formatting, protected-content
  sanitization, truncation, verification-summary extraction, and first-sentence
  summaries in package-private `ToolResultFormatter`.
- Kept compaction, retry/request extraction, path/call repair, stages, and
  `ExecutionOutcome` deferred.

## [2026-06-17] ledger | Close T831 result formatting

- Moved T831 to the done ticket ledger after focused formatter tests,
  privacy/context handoff suites, full `check`, architecture contract, and
  wiki evidence gates passed.
- Recorded implementation commit
  `93313de56b22a93a02af9515828e00a3a77947f8`.
- Set the next move to T832, Phase 1 evidence and characterization for
  in-turn compaction with no production `src/main` behavior change.

## [2026-06-17] evidence | Open T832 in-turn compaction Phase 1

- Added T832 as an evidence and characterization ticket for in-turn
  tool-loop compaction after T831 result-formatting extraction.
- Recorded source anchors for iteration-gated compaction, char-count-only
  `[compacted:]` stubs, retained `readFileBodiesThisTurn` state, and the
  separation from session-level `core.context` compaction.
- Added local artifact measurements and a behavioral characterization test
  without authorizing production compaction changes.

## [2026-06-17] ledger | Close T832 in-turn compaction Phase 1

- Moved T832 to the done ticket ledger after report-hygiene reconciliation
  recorded the exact artifact scan script.
- Preserved the Phase 1 finding: no proof of measurable answer-quality harm,
  with same-turn re-read evidence treated as a proxy only.
- Deferred gist-in-stub, token-pressure triggering, and prompt rehydration to
  later owner-ratified work.

## [2026-06-17] decision | Propose Wave 5 structural closeout

- Added a proposed Wave 5 structural-decomposition closeout record for owner
  ratification.
- Closed the executor, core/tools cycle, `ToolCallLoop` engine,
  `ToolCallExecutionStage` guard-chain, and highest-value `ToolCallSupport`
  structural seams as completed arcs.
- Recorded `LoopState`, `ExecutionOutcome`, remaining `ToolCallSupport` seams,
  retry extraction, and compaction quality work as deferred, not abandoned.

## [2026-06-17] decision | Ratify Wave 5 structural closeout

- Ratified the Wave 5 structural-decomposition closeout record after owner
  acceptance and fresh wiki/full-check evidence.
- Kept the explicit non-claims: `LoopState`, `ExecutionOutcome`, remaining
  `ToolCallSupport` seams, retry extraction, and compaction Phase 2 are
  deferred, not abandoned.
- Recorded that future Wave 5 follow-up work requires a new scoped ticket.

## [2026-06-18] disclosure | Open T833 Wave 6 trust-surface T0

- Opened T833 as the Wave 6 Tier 0 honest-disclosure pass after Wave 5
  ratification.
- Scoped T833 to README, AGENTS, docs, report/wiki, and a docs honesty test
  only; production `src/main`, site copy, capability work, compaction Phase 2,
  and the five HIGH code fixes remain out of scope.
- Folded T274, T276, T281, T283, T286, T301, and T319 into the Wave 6 trust
  track for later re-scoping; parked T294, T296, T299, T300, T302, T303, T304,
  and T627 as capability backlog, explicitly deferred.

## [2026-06-19] ledger | Close T833 Wave 6 trust-surface T0

- Moved T833 to the done ticket ledger after owner review of the honest
  disclosure pass and sanitized Wave 6 trust evidence record.
- Recorded commit `991ea37c734d788e1303c2f6e2e30e4b07177378`, which added the
  tracked sanitized evidence record and kept the raw audit local/untracked.
- Kept T834-T838 open as the high-priority Wave 6 trust-surface code-fix path;
  the next implementation move is T835, the chat transport localhost guard.

## [2026-06-19] code-fix | Implement T835 chat transport localhost guard

- Added default-deny locality enforcement for Ollama and llama.cpp chat model
  endpoints, with explicit remote opt-in through backend `allow_remote=true`.
- Added focused provider/locality tests and updated the trust-claims honesty
  guard so README, AGENTS, and docs state the post-T835 boundary.
- Left T835 open for review/closeout; T834/T836/T837/T838 remain open
  high-priority Wave 6 trust-surface fixes.

## [2026-06-20] ledger | Close T835 chat transport localhost guard

- Moved T835 to the done ticket ledger after review of implementation commit
  `c3b7170a38ad05a94920aace50382ad6b855f8de`.
- Updated the trust-claims honesty guard to pin the done ticket path and status.
- Recorded T839 embeddings host locality as the next scoped Wave 6
  trust-surface fix; T834/T836/T837/T838 remain open.

## [2026-06-20] code-fix | Implement T839 embedding host locality

- Added red tests showing both embedding clients accepted
  `127.0.0.1.evil.example` as local before the fix.
- Renamed `ChatHostLocalityPolicy` to neutral `HostLocalityPolicy` and reused
  the URI-based locality policy for Ollama and OpenAI-compatible embeddings.
- Left T839 open for review/closeout; T834/T836/T837/T838 remain open
  high-priority Wave 6 trust-surface fixes.

## [2026-06-20] ledger | Close T839 embedding host locality

- Moved T839 to the done ticket ledger after review of implementation commit
  `071af4e74d377c4cb38df06e12d0775f09942887`.
- Recorded that the focused locality/security/honesty set, full
  `check --no-daemon`, and `wikiEvidenceCloseGate --rerun-tasks --no-daemon`
  passed during review.
- Left T834/T836/T837/T838 open as the remaining Wave 6 high-priority
  trust-surface fixes; T834 strong redaction is the next planned fix.

## [2026-06-20] code-fix | Implement T834 strong redaction

- Added safety-layer strong secret-shape detection for model-facing and durable
  lower-layer sinks while keeping the existing `[redacted]` safety mask.
- Kept `core.security.Redactor` on its existing `[secret]` mask and made
  custom `redact.secrets` patterns additive with built-ins.
- Added red-first tests for bare token/JWT/PEM/connection-string/high-entropy
  shapes across standalone sanitizer, model-context handoff, session
  persistence, trace redaction, result formatting, and a direct lower-layer
  retrieve path.
- Left T834 open for review/closeout; T836/T837/T838 remain open.

## [2026-06-20] code-fix | Revise T834 high-entropy detector

- Removed the generic bounded high-entropy detector after adversarial review
  showed over-redaction of SRI hashes, base64 data URIs, and long mixed-case
  identifiers in the universal model/durable sanitizer path.
- Added deterministic AWS `AKIA`/`ASIA` access-key prefix detection and expanded
  the sanitizer negative corpus to keep SRI hashes, data URIs, base32-like
  strings, and long identifiers unchanged.
- Kept the targeted PEM, connection-string, token-prefix, `eyJ` JWT, and
  Redactor additive-builtins fixes intact; T834 remains open for review.

## [2026-06-20] ledger | Close T834 strong redaction

- Moved T834 to the done ticket ledger after review of implementation commits
  `cc0179103cec7d5d70797a886081fdc70a1c930c` and
  `61c6e0f41b3a51a716e78a19dee81495e1eab31c`.
- Updated the trust-claims honesty guard to pin the done ticket path and status.
- Recorded that focused T834 redaction/honesty tests, full
  `check --no-daemon`, `wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and
  `git diff --check -- . ':!site'` passed during closeout.
- Left T836/T837/T838 open as the remaining high-priority Wave 6 trust-surface
  fixes; T837 `run_command` output handoff is the next recommended model-context
  leakage boundary.

## [2026-06-20] code-fix | Implement T836 Windows protected-path canonicalization

- Canonicalized Windows trailing-dot and trailing-space path aliases before
  protected-token matching in the safety classifier.
- Classified Windows reserved device-name aliases as `CONTROL`.
- Bumped `ProtectedWorkspacePaths.POLICY_VERSION` to
  `protected-content-policy-v5` so stale RAG privacy partitions rebuild under
  the new protected-path classifier.
- Updated public documentation from the old Windows bypass caveat to the
  bounded current behavior wording.
- Left T836 open for review/closeout; T837/T838 remain open high-priority Wave
  6 trust-surface fixes.

## [2026-06-20] ledger | Close T836 Windows protected-path canonicalization

- Moved T836 to the done ticket ledger after review of implementation commit
  `bbab3bcd53c505d74160ace66cbe852eb2893509`.
- Updated the trust-claims honesty guard to pin the done ticket path and status.
- Recorded that focused safety/docs tests, runtime/privacy/architecture
  focused tests, full `check --no-daemon`,
  `wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and diff hygiene passed.
- Left T837/T838 open as the remaining high-priority Wave 6 trust-surface fixes.

## [2026-06-20] code-fix | Reopen T836 for NTFS 8.3 short-name alias

- Reopened T836 after review reproduced an NTFS 8.3 short-name bypass:
  `SSH~1/mykey`, `AWS~1/config`, and `AZURE~1/profile.json` reached protected
  `.ssh`, `.aws`, and `.azure` content while the closed T836 implementation
  classified those paths as ordinary in-workspace paths.
- Added a Windows-aware regression for short-name protected-directory aliases
  and new-file paths under `SSH~1`.
- Updated `ProtectedWorkspacePaths` to classify existing targets and nearest
  existing ancestors by OS real path before protected-token matching.
- Bumped the protected path policy version to `protected-content-policy-v6`.
- Left T836 open for review/closeout; T837/T838 remain open high-priority Wave
  6 trust-surface fixes.

## [2026-06-20] ledger | Close T836 after NTFS 8.3 follow-up

- Moved T836 back to the done ticket ledger after review of follow-up commit
  `56e2243569ce9b5329cb44c1bfcb6169e9bb54b1`.
- Updated the trust-claims honesty guard to pin the done ticket path and status.
- Recorded that the 8.3 regression ran on this Windows host, direct post-fix
  probing showed `SSH~1` aliases classifying as protected, focused tests, full
  `check --no-daemon`, `wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and
  diff hygiene passed.
- Left T837/T838 open as the remaining high-priority Wave 6 trust-surface fixes.

## [2026-06-20] code-fix | Implement T837 run_command output handoff

- Added command-output content metadata to executed `talos.run_command`
  results.
- Routed redacted command output through `ToolResultModelContextHandoff` so
  stdout/stderr that required protected-content redaction are replaced with a
  bounded model-context notice.
- Preserved normal non-sensitive command output visibility for verification
  answers and preserved failure-dominant behavior for failed/timed-out command
  runs.
- Updated public/docs wording and the trust-claims honesty guard to the bounded
  post-T837 claim.
- Left T837 open for review/closeout; T838 master-key custody remains open.

## [2026-06-20] closeout | Close T837 run_command output handoff

- Closed T837 after review of implementation commit
  `1fd15a44890043ec02566ba4951a4cec2b548152`.
- Recorded the bounded high-entropy withholding tradeoff: command streams are
  withheld rather than inline-mangled, command outcome status is preserved, and
  normal non-sensitive command output remains model-visible for verification.
- Repointed `TrustClaimsHonestyTest` from the open T837 ticket to the done
  ticket so the tracked evidence remains self-contained.
- Left T838 master-key custody as the remaining open high-priority Wave 6
  trust-surface fix.

## [2026-06-20] code-fix | Implement T838 master-key custody

- Added Windows DPAPI CurrentUser custody for `FileSecretStore` master keys so
  `.master.key` is a versioned protected blob on Windows, not raw AES key
  material.
- Preserved the per-entry AES-GCM format and migration compatibility: legacy
  raw master keys are protected in place without re-encrypting `.bin` entries
  and without leaving a persistent plaintext backup.
- Kept non-Windows custody unchanged and updated public/docs disclosure to the
  bounded Windows-only claim.
- Added focused custody, migration, fail-closed, and docs-honesty tests.
- Left T838 open for review of DPAPI shell-out security and migration safety.

## [2026-06-20] closeout | Close T838 master-key custody

- Closed T838 after review of implementation commit
  `7a4decca55b1dc3a1a4bbfbfbdf5c48517b046b3`.
- Repointed `TrustClaimsHonestyTest` from the open T838 ticket to the done
  ticket so the tracked Wave 6 trust evidence remains self-contained.
- Recorded that the DPAPI shell-out passes key material via stdin, uses fixed
  scripts, verifies legacy migration before atomic replacement, leaves no
  persistent plaintext backup, and keeps non-Windows custody bounded in docs.
- Recorded that focused custody/docs tests, full `check --no-daemon`,
  `wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and diff hygiene passed
  during review.
- Closed the last remaining Wave 6 high-priority trust-surface code-fix ticket;
  cheap consolidation follow-up requires a new scoped ticket.

## [2026-06-20] code-fix | Implement T840 protected-path realpath-failure fail-closed

- Opened T840 as a cheap consolidation follow-up after the Wave 6 high trust
  fixes.
- Added a post-realpath unresolved Windows 8.3-style short-name guard in
  `ProtectedWorkspacePaths` so surviving `~N` path segments fail closed as
  protected `CONTROL` rather than ordinary workspace paths.
- Bumped `ProtectedWorkspacePaths.POLICY_VERSION` to
  `protected-content-policy-v7` so stale protected-content privacy partitions
  rebuild.
- Added portable tests for unresolved `SSH~1` fail-closed behavior and negative
  coverage for ordinary safe paths and non-8.3 tilde names.
- Left T840 open for review/closeout.

## [2026-06-20] code-fix | Harden T840 short-name charset

- Broadened the unresolved Windows 8.3-style short-name guard to include
  `_`, `$`, `@`, and `-` in the short-name base and extension.
- Added portable regression coverage for surviving short-name shapes such as
  `ID_ED2~1`, `MY-KEY~1`, `$CACHE~1`, and `USER@1~1`.
- Preserved the shape-bound `~N` requirement so ordinary tilde names remain
  non-protected.

## [2026-06-20] closeout | Close T840 protected-path realpath-failure fail-closed

- Closed T840 after review of implementation commits
  `626e8ec4ab1c213dda3b6e3b2aadc414ac5ded95` and `93b73b44`.
- Recorded that unresolved Windows 8.3-style short-name segments now fail
  closed as protected `CONTROL` paths after realpath classification while
  successful realpath expansion and workspace-escape behavior remain
  unchanged.
- Recorded that focused safety/runtime tests, full `check --no-daemon`,
  `wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and diff hygiene passed.
- Left no active Wave 6 high trust-fix or cheap consolidation follow-up ticket.

## [2026-06-21] test-hardening | Harden competitor-claim honesty guard

- Added T841 as a tracked docs-honesty guard hardening follow-up after the
  local-only competitor matrix and absence-hardening research.
- Broadened `TrustClaimsHonestyTest` beyond exact `no competitor` so tracked
  README, AGENTS, and `docs/**` surfaces reject unqualified public phrases such
  as `no other assistant`, `only agentic coding tool`, `nobody else`, and
  `unlike every competitor`.
- Kept the local competitor reports in ignored `local/marketing/`; no
  competitor matrix data was promoted to tracked docs or the site.
- Left active Wave 6 high trust-fix and cheap consolidation tickets at none.

## [2026-06-21] pre-release | Open T843-T847 beta readiness track

- Implemented the scoped T843 site honesty copy fixes and wired `site` static
  honesty/build checks into `.github/workflows/beta-dev-ci.yml`; T843 remains
  open for owner copy acceptance.
- Added T844 RAG/vector and beta best-practices user docs, plus tracked docs
  honesty guards for bounded retrieval and best-effort redaction wording.
- Added T845 as an evidence-waiting model/hardware range report scaffold
  pending accepted T842 manual-test data.
- Implemented T846 doctor diagnostics for bounded runtime/hardware facts,
  retrieval/vector state, embedding host locality, BM25-only visibility, and
  explicit `GPU/VRAM not probed by Talos` wording.
- Added T847 as the next retrieval gold-context harness ticket so retrieval
  intelligence work starts from measured context quality.

## [2026-06-21] pre-release | Close T843/T844/T846 and populate T845

- Moved T843, T844, and T846 to the done ticket ledger after owner acceptance
  of the site copy, review acceptance of the RAG/vector docs and doctor
  diagnostics, and green focused, full `check`, wiki evidence, site, and diff
  gates.
- Populated T845 from the accepted local T842/Opus summaries while preserving
  the key caveat: the beta evidence exercised BM25-only retrieval, not
  hybrid/vector retrieval, even though the shipped default has
  `rag.vectors.enabled: true`.
- Left T845 open for review and T847 open as the next measurement-first
  retrieval/workspace-intelligence ticket.

## [2026-06-21] pre-release | Close T845 and ticket T842 correctness findings

- Moved T845 to the done ticket ledger after owner/Opus review accepted the
  narrow beta model/hardware evidence snapshot.
- Preserved the report caveats: one Windows 11 machine, managed `llama.cpp`,
  Qwen/GPT-OSS coverage, BM25-only retrieval, no validated hybrid/vector run,
  and unknown hardware/timing fields left unknown.
- Opened T848-T852 from the T842 manual findings before starting T847:
  mutation-intent fix-file classification, absent named-target guarding,
  read-only path/name grounding, read-display write containment, and GPT-OSS
  multi-document no-progress synthesis.
- Updated T847 so its retrieval follow-up themes remain valid but their ticket
  numbers will be assigned after the beta-correctness sequence.

## [2026-06-21] code-fix | Implement T848 fix-file mutation intent

- Added deterministic mutation-intent coverage for direct
  `fix <problem> in <file>` wording, including the audited
  `Fix the bug in calc.py.` prompt shape.
- Kept advisory and instructional variants read-only, including
  `How would you fix...`, `Can you explain how to fix...`, and
  `Should I fix...`.
- Pinned the resulting task contract and file-edit target tool surface so the
  direct fix request exposes `talos.read_file`, `talos.write_file`, and
  `talos.edit_file` behind the existing approval policy.
- Left T848 open for owner/Opus review before closeout.

## [2026-06-21] code-fix | Extend T848 to real scn-13 fix prompt

- Added a second T848 implementation pass after review showed the first pass
  covered the simplified `Fix the bug in calc.py.` shape but not the real T842
  scn-13 wording.
- The deterministic classifier now treats a file-scoped defect mention followed
  by an imperative fix sentence, for example `There is a bug in calc.py... Fix
  multiply...`, as a mutation-capable file-edit request.
- Added regression coverage across `MutationIntentTest`,
  `TaskContractResolverTest`, and `ToolSurfacePlannerTest`, while preserving
  advisory/explanatory/no-change variants as non-mutating.
- T848 remains open for owner/Opus review and live scn-13 rerun before closeout.

## [2026-06-21] code-fix | Harden T848 fix-it false positives

- Added a third T848 implementation pass after adversarial live prompts showed
  old `fix it` markers could still expose mutation tools for read-only
  questions and explicit no-fix requests.
- `do not fix`, `don't fix`, and `dont fix` now count as global read-only
  negations.
- Embedded advisory pronoun questions such as `There is a bug in calc.py. How
  would you fix it?` now stay non-mutating, while the real scn-13 imperative
  `Fix multiply...` prompt remains mutation-capable.
- T848 remains open for review and live rerun of scn-13 plus the adversarial
  question/no-fix prompts before closeout.

## [2026-06-21] code-fix | Harden T848 modal self-questions

- Added a fourth T848 implementation pass after live probing showed
  `There is a bug in calc.py. Should I fix it?` still exposed mutation tools
  through the old `fix it` marker.
- Embedded modal self-questions with `I|we`, such as `Should I fix it?`,
  `Can I fix it?`, and `Could we fix it?`, now stay non-mutating.
- Assistant-directed modal requests with `you`, such as `Can you fix it?`, stay
  mutation-capable, as does the real scn-13 imperative `Fix multiply...` prompt.
- T848 remains open for review and the live scn-13b-f battery before closeout.

## [2026-06-21] code-fix | Implement T851 read-display write containment

- Added a pre-approval guard for `write_file` and `edit_file` payloads that
  carry Talos `read_file` display prefixes such as `N | ...` for a same-turn
  read target.
- Added direct guard tests and a `ToolCallLoop` regression proving the poisoned
  payload fails before approval and leaves the target file unchanged.
- Left T851 open pending the live T842/scn-14 corruption probe on both beta
  models before closeout.

## [2026-06-21] closeout | Close T851 read-display write containment

- Reran the current installed build against the existing T842/scn-14
  absent-target corruption fixture on both beta models. `helper.py` stayed
  unchanged on `qwen2.5-coder-14b` and `gpt-oss-20b`; both runs failed honestly
  before approval because `foo()` was absent.
- Added a target-present live corruption probe to exercise T851 directly.
  Qwen made a clean edit without copying `N |` prefixes. GPT-OSS first attempted
  a contaminated `write_file` payload, which the T851 guard blocked with
  `READ_DISPLAY_WRITE_CONTAINMENT` / `READ_DISPLAY_PREFIX_WRITE`; the model then
  retried with clean content.
- Closed T851. T850 read-only path/name grounding and T852 GPT-OSS
  multi-document no-progress synthesis remain open.

## [2026-06-22] code-fix | Implement T850 file-grounded answer framing

- Added a deterministic current-turn `[FileGroundedAnswer]` frame for
  non-mutating read-only/workspace-inspection turns.
- The frame now states that workspace path/name metadata is not file evidence,
  that paths are location labels only, and that directory names must not be
  presented as project names or other file-grounded facts unless observed in
  current-turn read/search/list results.
- Added a red-first `CurrentTurnCapabilityFrameTest` regression for the T842
  scn-10 prompt shape. T850 remains open pending the qwen scn-10 live rerun
  before closeout.
