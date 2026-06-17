---
wiki_schema: talos.wikiPage.v1
title: "Talos Living Evidence Wiki"
kind: index
status: active
last_verified_commit: "496799a46ca131a0d8164e49e2a6be130efe6e69"
evidence_inputs:
  - type: repo_file
    ref: "AGENTS.md"
    selector: "Talos Development, Work-Test, And Audit Instructions"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T807-done-high] architecture-intelligence-report-discipline.md"
    selector: "Architecture intelligence report discipline"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T808-done-high] living-evidence-wiki-discipline.md"
    selector: "Living evidence wiki discipline"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T809-done-high] wiki-evidence-liveness-lint.md"
    selector: "Wiki evidence-liveness lint"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T810-done-high] living-wiki-operating-loop-and-close-gate.md"
    selector: "Living wiki operating loop and close gate"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T811-done-high] assistant-turn-executor-lifecycle-ownership-characterization.md"
    selector: "First Wave 5 lifecycle ownership ticket and turn-preparation extraction"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T812-done-high] assistant-turn-executor-model-dispatch-characterization.md"
    selector: "Model dispatch characterization"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T813-done-high] assistant-turn-executor-model-dispatch-extraction.md"
    selector: "Model dispatch extraction"
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
    ref: "work-cycle-docs/tickets/done/[T824-done-high] tool-call-loop-engine-extraction.md"
    selector: "ToolCallLoopEngine extraction closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T825-done-high] tool-loop-internals-boundary-scoping.md"
    selector: "Tool-loop internals boundary scoping closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T826-done-high] tool-call-execution-stage-characterization.md"
    selector: "ToolCallExecutionStage characterization closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T827-done-high] architecture-intelligence-qodana-summary-ordering.md"
    selector: "Qodana summary ordering closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T828-done-high] tool-call-execution-stage-guard-chain-extraction.md"
    selector: "Guard chain extraction closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T829-done-high] tool-call-support-boundary-scoping.md"
    selector: "ToolCallSupport boundary scoping closeout"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T830-done-high] tool-call-support-native-call-conversion-extraction.md"
    selector: "Completion Evidence"
  - type: repo_file
    ref: "work-cycle-docs/reports/t819-core-tools-cycle-edge-scoping.md"
    selector: "Generated Package Evidence"
  - type: repo_file
    ref: "work-cycle-docs/reports/t823-tool-call-loop-orchestration-characterization.md"
    selector: "ToolCallLoop orchestration boundary"
  - type: repo_file
    ref: "work-cycle-docs/reports/t825-tool-loop-internals-boundary-scoping.md"
    selector: "Tool-loop internals boundary scoping"
  - type: repo_file
    ref: "work-cycle-docs/reports/t826-tool-call-execution-stage-characterization.md"
    selector: "ToolCallExecutionStage characterization"
  - type: repo_file
    ref: "work-cycle-docs/reports/t829-tool-call-support-boundary-scoping.md"
    selector: "ToolCallSupport boundary scoping"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 11
  DETERMINISTIC_STATIC: 17
  DETERMINISTIC_GENERATED: 0
  OBSERVED_RUNTIME: 0
  GATED: 0
---

# Talos Living Evidence Wiki

This wiki is the committed synthesis layer for current Talos engineering
understanding. It does not replace policy, runbooks, release ledgers, tickets,
or generated reports.

## Entry Points

- [CURRENT-STATE.md](CURRENT-STATE.md) - current branch, version, active work,
  caveats, and next move.
- [WIKI-SCHEMA.md](WIKI-SCHEMA.md) - page schema, confidence ladder, and update
  rules.
- [EVIDENCE-REGISTRY.md](EVIDENCE-REGISTRY.md) - stable IDs for generated
  evidence used by machine-checkable wiki claims.
- [LOG.md](LOG.md) - reviewed wiki change log.
- [concepts/living-evidence-wiki.md](concepts/living-evidence-wiki.md) -
  rationale and operating model.

## Evidence Boundaries

- [AGENTS.md](../../AGENTS.md) remains repository policy.
- Runbooks remain procedural instructions.
- Generated `build/` reports remain current machine evidence.
- Release packets, GATES files, and ADR-style decisions remain immutable
  ledger artifacts.
- Tickets remain task ownership and acceptance records.
- The evidence registry maps stable IDs to generated evidence that the wiki
  close gate can refresh.
- Wiki pages are source-backed synthesis for future engineering behavior.

## Active Tickets

- None.

Next planned move: open T831 result-formatting extraction with
`ToolCallSupport` and `ToolCallLoop` delegates preserved.

## Update Rule

Only promote a conclusion into the wiki when it is resolved, source-backed, and
likely to affect future engineering behavior. Chat history alone is not
evidence unless converted into a tracked research note or backed by repo,
generated-report, ticket, runbook, release-ledger, ADR, or external-source
citations.
