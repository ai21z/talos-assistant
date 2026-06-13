---
wiki_schema: talos.wikiPage.v1
title: "Talos Living Evidence Wiki"
kind: index
status: active
last_verified_commit: "b871e208b7bb03a5487f9b638ad02af613f52a78"
evidence_inputs:
  - type: repo_file
    ref: "AGENTS.md"
    selector: "Talos Development, Work-Test, And Audit Instructions"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T807-in-progress-high] architecture-intelligence-report-discipline.md"
    selector: "Architecture intelligence report discipline"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T808-open-high] living-evidence-wiki-discipline.md"
    selector: "Living evidence wiki discipline"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T809-open-high] wiki-evidence-liveness-lint.md"
    selector: "Wiki evidence-liveness lint"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T810-open-high] living-wiki-operating-loop-and-close-gate.md"
    selector: "Living wiki operating loop and close gate"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 1
  DETERMINISTIC_STATIC: 4
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

- [T807 architecture intelligence report discipline](../tickets/open/%5BT807-in-progress-high%5D%20architecture-intelligence-report-discipline.md)
- [T808 living evidence wiki discipline](../tickets/open/%5BT808-open-high%5D%20living-evidence-wiki-discipline.md)
- [T809 wiki evidence-liveness lint](../tickets/open/%5BT809-open-high%5D%20wiki-evidence-liveness-lint.md)
- [T810 living wiki operating loop and close gate](../tickets/open/%5BT810-open-high%5D%20living-wiki-operating-loop-and-close-gate.md)

## Update Rule

Only promote a conclusion into the wiki when it is resolved, source-backed, and
likely to affect future engineering behavior. Chat history alone is not
evidence unless converted into a tracked research note or backed by repo,
generated-report, ticket, runbook, release-ledger, ADR, or external-source
citations.
