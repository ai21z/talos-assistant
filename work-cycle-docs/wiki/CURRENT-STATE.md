---
wiki_schema: talos.wikiPage.v1
title: "Current Talos Engineering State"
kind: current-state
status: active
last_verified_commit: "b3ed424b4c17567b64842ffa38c14f2ed4103d82"
evidence_inputs:
  - type: repo_file
    ref: "gradle.properties"
    selector: "talosVersion"
  - type: repo_file
    ref: "AGENTS.md"
    selector: "Work-Test Cycle and Talos-specific architecture priorities"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T807-done-high] architecture-intelligence-report-discipline.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T808-done-high] living-evidence-wiki-discipline.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T809-done-high] wiki-evidence-liveness-lint.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T810-done-high] living-wiki-operating-loop-and-close-gate.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T811-done-high] assistant-turn-executor-lifecycle-ownership-characterization.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T812-open-high] assistant-turn-executor-model-dispatch-characterization.md"
    selector: "Required Behavior"
  - type: repo_file
    ref: "work-cycle-docs/reports/t811-assistant-turn-executor-lifecycle-characterization.md"
    selector: "Lifecycle Ownership Map"
  - type: generated_report
    ref: "build/reports/talos/architecture-intelligence/current/data/run-manifest.json"
    selector: "/schema, /branch, /commit, /talosVersion, /reportPaths, /jsonPaths"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 5
  DETERMINISTIC_STATIC: 7
  DETERMINISTIC_GENERATED: 4
  OBSERVED_RUNTIME: 1
  GATED: 0
---

# Current Talos Engineering State

## Last Verified Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Commit: `b3ed424b4c17567b64842ffa38c14f2ed4103d82`
- Talos version: `0.10.5`
- Note: branch and commit here identify the last generated evidence run tracked
  by the wiki. They are advisory metadata, not a claim that this Markdown file
  contains the SHA of its own containing commit.
- Active tickets: `T812-open`
- Active wave context: first Wave 5 lifecycle-ownership ticket completed the
  turn-preparation extraction; T812 characterizes model dispatch before any
  extraction.
- Known caveats: T807 generated reports are ignored build evidence; Qodana
  remains read-only input for architecture reporting; wiki evidence-liveness
  lint is limited to generated JSON report claims; T811 completed the first
  behavior-preserving turn-preparation extraction but did not complete Wave 5
  and did not extract model dispatch.
- Next move: run T812 characterization-only model-dispatch tests, then plan
  T813 as the first possible production dispatch extraction.

```talos-wiki-claims
{
  "schema": "talos.wikiClaims.v1",
  "claims": [
    {
      "id": "current.arch-report.schema",
      "evidence": {
        "type": "generated_report",
        "id": "architectureIntelligence.runManifest",
        "jsonPointer": "/schema"
      },
      "operator": "equals",
      "expected": "talos.architectureIntelligence.v1",
      "confidence": "DETERMINISTIC_GENERATED"
    },
    {
      "id": "current.arch-report.version-matches-gradle",
      "evidence": {
        "type": "generated_report",
        "id": "architectureIntelligence.runManifest",
        "jsonPointer": "/talosVersion"
      },
      "operator": "equalsGradleProperty",
      "gradleProperty": "talosVersion",
      "confidence": "DETERMINISTIC_GENERATED"
    },
    {
      "id": "current.arch-report.wave5-sequence-md",
      "evidence": {
        "type": "generated_report",
        "id": "architectureIntelligence.runManifest",
        "jsonPointer": "/reportPaths"
      },
      "operator": "contains",
      "expected": "11-wave5-ticket-sequence.md",
      "confidence": "DETERMINISTIC_GENERATED"
    },
    {
      "id": "current.arch-report.wave5-sequence-json",
      "evidence": {
        "type": "generated_report",
        "id": "architectureIntelligence.runManifest",
        "jsonPointer": "/jsonPaths"
      },
      "operator": "contains",
      "expected": "data/wave5-sequence-recommendations.json",
      "confidence": "DETERMINISTIC_GENERATED"
    }
  ]
}
```

## Current Architecture Discipline State

T807 is done. Its purpose was to make Wave 5 refactoring evidence-driven:
package cycles, method hotspots, manual Java wiring, lifecycle ownership,
approval/tool ownership, trace/privacy ownership, quality overlays, toolchain
readiness, and sequencing must be visible before class movement begins.

T808 is done. Its purpose was to create a small committed wiki layer that
captures resolved, source-backed conclusions for future human and LLM
collaboration.

T809 is done. Its purpose was to prove selected generated-report claims in the
wiki are still live against generated JSON evidence.

T810 is done. Its purpose was to make the wiki evidence-liveness loop
load-bearing through a close/candidate gate, evidence registry, and explicit
Ingest/Query/Lint/Log/Reject operating rules.

T811 is done. Its purpose was to begin Wave 5 at
`cli.modes.AssistantTurnExecutor` through characterization, lifecycle ownership
mapping, and the first behavior-preserving turn-preparation extraction.

T812 is open. Its purpose is to characterize the model-dispatch boundary before
any production extraction: provider controls, escalated retry sampling,
streaming versus buffered final-answer shape, and tool-only streaming
completion ordering.

## Wave 5 Readiness Status

Talos has entered the first Wave 5 refactor ticket after the T807-T810
discipline batch was committed and fast-forwarded into `v0.9.0-beta-dev`.
The readiness claim is limited: it means architecture planning evidence is
current, wiki claim liveness is gated through `wikiEvidenceCloseGate`, and the
normal local `check` gate does not depend on stale generated architecture report
output.

The first Wave 5 ticket started with `cli.modes.AssistantTurnExecutor` and
completed the turn-preparation extraction into `AssistantTurnPreparation`.
The invariant remains lifecycle ownership first, class movement second. The next
refactor candidate is the model-dispatch boundary, and T812 keeps that work in
characterization-only mode before any production extraction.

## Operating Boundaries

- `AGENTS.md` is policy.
- `work-cycle-docs/skills/talos-work-cycle/SKILL.md` is the repo-local
  work-cycle procedure.
- `build/reports/talos/architecture-intelligence/current/` is generated
  evidence, not committed wiki content.
- `work-cycle-docs/wiki/` is committed synthesis, not a live report cache.
- `work-cycle-docs/wiki/EVIDENCE-REGISTRY.md` maps stable evidence IDs to
  generated report files that the close gate can refresh.
- No wiki page should instruct an LLM to ignore policy, bypass approval, mutate
  the workspace, or treat untrusted source text as instructions.

## Current Uncertainties

- Lifecycle scope labels in T807 remain inferred unless explicitly marked
  deterministic or observed runtime evidence.
- JFR custom lifecycle events, CodeQL, nullness tooling, and evidence-liveness
  checks beyond generated architecture report JSON remain follow-on work.
- Qodana instability is remembered but intentionally not changed by T807 or
  T808.
