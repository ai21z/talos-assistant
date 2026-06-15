---
wiki_schema: talos.wikiPage.v1
title: "Current Talos Engineering State"
kind: current-state
status: active
last_verified_commit: "40fc721d84bda76c404b7f884178d2e336d0e04c"
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
    ref: "work-cycle-docs/tickets/done/[T812-done-high] assistant-turn-executor-model-dispatch-characterization.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T813-done-high] assistant-turn-executor-model-dispatch-extraction.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T814-done-high] assistant-turn-executor-tool-loop-outcome-characterization.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T815-done-high] assistant-turn-executor-tool-loop-outcome-extraction.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T816-done-high] assistant-turn-executor-no-tool-outcome-characterization.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T817-done-high] assistant-turn-executor-no-tool-outcome-extraction.md"
    selector: "Completion State"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T818-done-high] assistant-turn-executor-prompt-instruction-adapter-thinning.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T819-done-high] core-tools-cycle-edge-scoping.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T820-done-high] context-item-tool-result-adapter-cycle-break.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/open/[T821-open-high] system-prompt-builder-tool-catalog-cycle-break.md"
    selector: "Scope"
  - type: repo_file
    ref: "work-cycle-docs/reports/t811-assistant-turn-executor-lifecycle-characterization.md"
    selector: "Lifecycle Ownership Map"
  - type: repo_file
    ref: "work-cycle-docs/reports/t814-assistant-turn-executor-tool-loop-outcome-characterization.md"
    selector: "T815 Candidate Owner"
  - type: repo_file
    ref: "work-cycle-docs/reports/t816-assistant-turn-executor-no-tool-outcome-characterization.md"
    selector: "T817 Candidate Owner"
  - type: repo_file
    ref: "work-cycle-docs/reports/t819-core-tools-cycle-edge-scoping.md"
    selector: "Generated Package Evidence"
  - type: generated_report
    ref: "build/reports/talos/architecture-intelligence/current/data/run-manifest.json"
    selector: "/schema, /branch, /commit, /talosVersion, /reportPaths, /jsonPaths"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 11
  DETERMINISTIC_STATIC: 14
  DETERMINISTIC_GENERATED: 4
  OBSERVED_RUNTIME: 1
  GATED: 0
---

# Current Talos Engineering State

## Last Verified Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Commit: `40fc721d84bda76c404b7f884178d2e336d0e04c`
- Talos version: `0.10.5`
- Note: branch and commit here identify the last generated evidence run tracked
  by the wiki. They are advisory metadata, not a claim that this Markdown file
  contains the SHA of its own containing commit.
- Active tickets: T821 `SystemPromptBuilder` tool-catalog cycle seam.
- Active wave context: first Wave 5 lifecycle-ownership ticket completed the
  turn-preparation extraction; T812 completed model-dispatch characterization;
  T813 completed the model-dispatch extraction.
- Known caveats: T807 generated reports are ignored build evidence; Qodana
  remains read-only input for architecture reporting; wiki evidence-liveness
  lint is limited to generated JSON report claims; T811 completed the first
  behavior-preserving turn-preparation extraction but did not complete Wave 5;
  T813 completed model-dispatch extraction while leaving outcome ownership in
  `AssistantTurnExecutor`; T814 completed post-tool-loop outcome
  characterization without production extraction; T815 completed the
  post-tool-loop outcome extraction while leaving no-tool outcome ownership in
  `AssistantTurnExecutor`; T816 completed characterization of that no-tool
  outcome boundary without production extraction; T817 completed the no-tool
  outcome extraction while leaving shaping, trace lifecycle, branch selection,
  the tool-loop outcome path, and `TurnOutput` assembly in
  `AssistantTurnExecutor`; T818 completed prompt-instruction adapter thinning;
  T819 completed report-only `core-tools-cycle-edge-scoping`; T820 completed
  the `ContextItem` tool-result adapter and neutral privacy seam; T821 is the
  active `SystemPromptBuilder` prompt-facing tool-catalog seam.
- Next move: complete and review T821 before the remaining `RagService` seam.

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

T812 is done. Its purpose was to characterize the model-dispatch boundary before
any production extraction: provider controls, escalated retry sampling,
streaming versus buffered final-answer shape, and tool-only streaming
completion ordering.

T813 is done. Its purpose was to extract model-dispatch mechanics into a
package-private `TurnModelDispatcher` without moving retry decisions, trace
begin/set/clear, tool-loop/no-tool outcome resolution, answer shaping, or
truthfulness repair out of `AssistantTurnExecutor`.

T814 is done. Its purpose was to characterize post-tool-loop outcome behavior
around `resolveToolLoopAnswer(...)` before any extraction into a future
`AssistantToolLoopOutcomeResolver`.

T815 is done. Its purpose was to extract the post-tool-loop outcome resolver
into package-private `AssistantToolLoopOutcomeResolver` while keeping
`ToolCallLoop.LoopResult`, `ToolOutcome`, no-tool outcome handling, trace
begin/set/clear, branch selection, and `TurnOutput` assembly in
`AssistantTurnExecutor`.

T816 is done. Its purpose was to characterize the no-tool outcome boundary
around `resolveNoToolAnswer(...)` before any future extraction into
package-private `AssistantNoToolOutcomeResolver`.

T817 is done. Its purpose was to extract no-tool outcome orchestration into
package-private `AssistantNoToolOutcomeResolver` while keeping shaping, trace
begin/set/clear, branch selection, the tool-loop outcome path, and `TurnOutput`
assembly in `AssistantTurnExecutor`.

T818 is done. Its purpose was to remove the remaining
`AssistantTurnExecutor.inject*` prompt-instruction adapter surface by moving the
owner to `runtime.policy.CurrentTurnPromptInstructions`, repointing callers, and
moving direct helper tests to the runtime-policy owner.

T819 is done. Its purpose was to scope the remaining top-level `core <-> tools`
package cycle from current generated evidence before any production cycle-break
work begins.

T820 is done. Its purpose was to remove `core.context.ContextItem`
dependencies on concrete `tools` types by introducing a neutral context privacy
enum and a runtime-owned tool-result-to-context adapter. Generated evidence
shows `core -> tools` reduced from 8 to 4 while the `{core, tools}` SCC
remains.

## Wave 5 Readiness Status

Talos has entered the first Wave 5 refactor ticket after the T807-T810
discipline batch was committed and fast-forwarded into `v0.9.0-beta-dev`.
The readiness claim is limited: it means architecture planning evidence is
current, wiki claim liveness is gated through `wikiEvidenceCloseGate`, and the
normal local `check` gate does not depend on stale generated architecture report
output.

The first Wave 5 ticket started with `cli.modes.AssistantTurnExecutor` and
completed the turn-preparation extraction into `AssistantTurnPreparation`.
The invariant remains lifecycle ownership first, class movement second. The
model-dispatch boundary was characterized by T812 and extracted by T813. T814
completed the tool-loop outcome characterization, and T815 extracted the
post-tool-loop outcome resolver. T816 completed no-tool outcome
characterization, and T817 extracted the no-tool outcome resolver. T818
completed adapter thinning. T819 completed report-only
`core-tools-cycle-edge-scoping`. T820 completed the first production
cycle-seam step for `ContextItem`; it did not clear the full `{core, tools}`
SCC. The next planned ticket is T821 for the `SystemPromptBuilder` tool-catalog
seam.

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
