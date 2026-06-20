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
    ref: "work-cycle-docs/tickets/open/[T831-open-high] tool-call-support-result-formatting-extraction.md"
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
