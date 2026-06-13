---
wiki_schema: talos.wikiPage.v1
title: "Current Talos Engineering State"
kind: current-state
status: active
last_verified_commit: "b871e208b7bb03a5487f9b638ad02af613f52a78"
evidence_inputs:
  - type: repo_file
    ref: "gradle.properties"
    selector: "talosVersion"
  - type: repo_file
    ref: "AGENTS.md"
    selector: "Work-Test Cycle and Talos-specific architecture priorities"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T807-in-progress-high] architecture-intelligence-report-discipline.md"
    selector: "Current Evidence and Hardening Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T808-open-high] living-evidence-wiki-discipline.md"
    selector: "Required Behavior"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T809-open-high] wiki-evidence-liveness-lint.md"
    selector: "Required Behavior"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T810-open-high] living-wiki-operating-loop-and-close-gate.md"
    selector: "Required Behavior"
  - type: generated_report
    ref: "build/reports/talos/architecture-intelligence/current/data/run-manifest.json"
    selector: "/schema, /branch, /commit, /talosVersion, /reportPaths, /jsonPaths"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 4
  DETERMINISTIC_STATIC: 6
  DETERMINISTIC_GENERATED: 4
  OBSERVED_RUNTIME: 1
  GATED: 0
---

# Current Talos Engineering State

## Run Identity

- Branch: `feature/wave4-ergonomics`
- Commit: `b871e208b7bb03a5487f9b638ad02af613f52a78`
- Talos version: `0.10.5`
- Active tickets: `T807`, `T808`, `T809`, `T810`
- Active wave context: pre-Wave 5 architecture/report discipline hardening.
- Known caveats: T807 generated reports are ignored build evidence; Qodana is
  read-only input for T807; wiki evidence-liveness lint is limited to generated
  JSON report claims in T809; no Wave 5 refactor has started in T807, T808, or
  T809; T810 adds the operating loop and close gate without starting Wave 5.
- Next move: commit the T807/T808/T809/T810 discipline batch after owner
  review, then open the first Wave 5 refactor ticket against
  `cli.modes.AssistantTurnExecutor` with lifecycle ownership as the primary
  invariant.

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

T807 is in progress. Its purpose is to make Wave 5 refactoring evidence-driven:
package cycles, method hotspots, manual Java wiring, lifecycle ownership,
approval/tool ownership, trace/privacy ownership, quality overlays, toolchain
readiness, and sequencing must be visible before class movement begins.

T808 is open. Its purpose is to create a small committed wiki layer that
captures resolved, source-backed conclusions for future human and LLM
collaboration.

T809 is open. Its purpose is to prove selected generated-report claims in the
wiki are still live against generated JSON evidence.

T810 is open. Its purpose is to make the wiki evidence-liveness loop
load-bearing through a close/candidate gate, evidence registry, and explicit
Ingest/Query/Lint/Log/Reject operating rules.

## Wave 5 Readiness Status

Talos is ready for the first Wave 5 refactor ticket after the T807-T810
discipline batch is committed. The readiness claim is limited: it means
architecture planning evidence is current, wiki claim liveness is gated through
`wikiEvidenceCloseGate`, and the normal local `check` gate does not depend on
stale generated architecture report output.

The first Wave 5 ticket should start with `cli.modes.AssistantTurnExecutor`.
The invariant is lifecycle ownership first, class movement second.

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
