---
wiki_schema: talos.wikiPage.v1
title: "Current Talos Engineering State"
kind: current-state
status: active
last_verified_commit: "141d787269e1d21baaf42435623bbe80b14a94c2"
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
    ref: "work-cycle-docs/tickets/done/[T821-done-high] system-prompt-builder-tool-catalog-cycle-break.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T822-done-high] rag-tool-protocol-text-cycle-break.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T823-done-high] tool-call-loop-orchestration-characterization.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T824-done-high] tool-call-loop-engine-extraction.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T825-done-high] tool-loop-internals-boundary-scoping.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T826-done-high] tool-call-execution-stage-characterization.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T827-done-high] architecture-intelligence-qodana-summary-ordering.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T828-done-high] tool-call-execution-stage-guard-chain-extraction.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T829-done-high] tool-call-support-boundary-scoping.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T830-done-high] tool-call-support-native-call-conversion-extraction.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T831-done-high] tool-call-support-result-formatting-extraction.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T832-done-high] in-turn-compaction-evidence-and-conditional-gist.md"
    selector: "Completion Evidence"
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T833-done-high] wave6-trust-surface-honest-disclosure.md"
    selector: "Completion Evidence"
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
  - type: repo_file
    ref: "work-cycle-docs/reports/t823-tool-call-loop-orchestration-characterization.md"
    selector: "T824 Candidate Owner"
  - type: repo_file
    ref: "work-cycle-docs/reports/t825-tool-loop-internals-boundary-scoping.md"
    selector: "Candidate T826 Owners"
  - type: repo_file
    ref: "work-cycle-docs/reports/t826-tool-call-execution-stage-characterization.md"
    selector: "Characterized Behavior"
  - type: repo_file
    ref: "work-cycle-docs/reports/t829-tool-call-support-boundary-scoping.md"
    selector: "Candidate T830 Seam Hypotheses"
  - type: repo_file
    ref: "work-cycle-docs/reports/t832-in-turn-compaction-evidence-and-conditional-gist.md"
    selector: "Answer Quality Finding"
  - type: repo_file
    ref: "work-cycle-docs/reports/wave5-structural-decomposition-closeout-ratified.md"
    selector: "Decision"
  - type: repo_file
    ref: "work-cycle-docs/reports/t833-wave6-trust-surface-honest-disclosure.md"
    selector: "Bounded Trust Claims"
  - type: generated_report
    ref: "build/reports/talos/architecture-intelligence/current/data/run-manifest.json"
    selector: "/schema, /branch, /commit, /talosVersion, /reportPaths, /jsonPaths"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 17
  DETERMINISTIC_STATIC: 22
  DETERMINISTIC_GENERATED: 4
  OBSERVED_RUNTIME: 1
  GATED: 0
---

# Current Talos Engineering State

## Last Verified Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Commit: `141d787269e1d21baaf42435623bbe80b14a94c2`
- Talos version: `0.10.5`
- Note: branch and commit here identify the last generated evidence run tracked
  by the wiki. They are advisory metadata, not a claim that this Markdown file
  contains the SHA of its own containing commit.
- Active tickets: T836 Windows protected-path canonicalization (implemented,
  open for review); T837
  `run_command` output handoff boundary; T838 master-key custody.
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
  the `ContextItem` tool-result adapter and neutral privacy seam; T821
  completed the `SystemPromptBuilder` prompt-facing tool-catalog seam while
  leaving the final `RagService` / `ToolProtocolText` production edge; T822
  completed the final planned `core -> tools` cycle-break seam and cleared the
  top-level `{core, tools}` SCC; T823 completed characterization-only
  `ToolCallLoop` orchestration evidence and did not authorize extraction by
  itself; T824 completed the behavior-preserving `ToolCallLoopEngine`
  extraction while keeping `ToolCallLoop` as the public facade; T825 completed
  scoping of the remaining `runtime.toolcall` internals; T826 completed direct
  characterization of `ToolCallExecutionStage.execute(...)`; T828 completed
  the first production `ToolCallExecutionStage` decomposition; T829 completed
  `ToolCallSupport` boundary scoping and selected native-call conversion as
  the first production seam; T830 completed that extraction; T831 completed
  result-formatting extraction. T832 completed Phase 1 evidence and
  characterization for in-turn compaction with no production `src/main`
  behavior change.
- Next move: Wave 5 structural-decomposition closeout is owner-ratified;
  future Wave 5 follow-up work requires new scoped tickets. Wave 6 Tier 0
  honest disclosure is complete through T833; T835, the chat transport
  localhost guard, is done. T839, the embedding host locality policy fix, is
  done. T834 strong redaction is done; T836 is implemented and open for
  review/closeout; T837/T838 remain open high-priority fixes.

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

T821 is done. Its purpose was to remove the `core.llm.SystemPromptBuilder`
dependency on executable tool registry types by introducing neutral
prompt-facing descriptors and a runtime-owned registry adapter. Generated
evidence shows `core -> tools` reduced to 1 while the `{core, tools}` SCC
remains because `core.rag.RagService` still imports tool-protocol cleanup.

T822 is done. Its purpose was to remove the final production `core -> tools`
dependency by moving non-executing tool-protocol text cleanup and tool-name
alias recognition behind neutral core owners while keeping compatibility
wrappers in `tools`. Regenerated architecture evidence at implementation
commit `916d0780bcb49da747e9894b34f3f5412a4b2f87` reports `core -> tools = 0`
and no non-trivial top-level package SCCs.

T823 is done. Its purpose was to characterize `runtime.ToolCallLoop`
orchestration before extraction into package-private
`dev.talos.runtime.ToolCallLoopEngine`. It was test/report/ticket work only and
did not move production code.

T824 is done. Its purpose was to extract `ToolCallLoop` orchestration into
package-private `dev.talos.runtime.ToolCallLoopEngine` while keeping
`ToolCallLoop` as the public facade with stable `LoopResult`, `ToolOutcome`,
constructors, `run(...)` overloads, and static helper delegates. It preserved
the package-private final-answer finalizer boundary and left `LoopState`,
`ToolCallSupport`, `ToolCallExecutionStage`, `ToolCallParseStage`,
`ToolCallRepromptStage`, `ExecutionOutcome`, and tool model types in place for
later scoping.

T825 is done. Its purpose was to scope the remaining `runtime.toolcall`
internals after T824. It recorded the current `INFERRED_REVIEW` hotspot
evidence for `ToolCallLoop`, `LoopState`, `ToolCallSupport`, and
`ToolCallExecutionStage`; named deferred higher-ranked non-toolcall hotspots;
selected T826 `ToolCallExecutionStage` characterization as the next step; and
did not authorize production extraction.

T826 is done. It characterized
`runtime.toolcall.ToolCallExecutionStage.execute(...)` directly before any
production decomposition. It pins text/native result-message shape, approval
denial flags, private-document path-policy blocking, context-ledger decisions,
successful execution accounting, failed edit accounting, and the public
`IterationOutcome` surface.

T827 is done. It hardened architecture intelligence / wiki-evidence ordering so
`qodana-summary.json` is generated before report validation reads it.

T828 is done. Its purpose was to extract the pre-execution guard chain from
`ToolCallExecutionStage.execute(...)` into package-private
`ToolCallPreExecutionGuardChain` while preserving the public stage API,
result-message shape, approval/trace/ledger behavior, mutation/failure
accounting, and edit-repair ordering.

T829 is done. Its purpose was to characterize and scope the broad
`ToolCallSupport` helper surface before choosing a T830 production extraction
seam. It kept result formatting, retry/request extraction, path/call repair,
and compaction as hypotheses, and selected native-call conversion as the first
narrow T830 production extraction.

T830 is done. Its purpose was to extract native-call conversion from
`ToolCallSupport` into package-private `NativeToolCallConverter` while keeping
public/static compatibility delegates stable and leaving result formatting,
retry/request extraction, path/call repair, compaction, and trust-surface
redaction deferred. Implementation commit
`496799a46ca131a0d8164e49e2a6be130efe6e69` preserved container JSON
rendering, legacy scalar stringification, null argument maps, and serialization
fallback behavior.

T831 is done. Its purpose was to extract prompt-visible result formatting from
`ToolCallSupport` into package-private `ToolResultFormatter` while preserving
public/static delegates and leaving compaction, retry/request extraction,
path/call repair, stages, and `ExecutionOutcome` deferred.

T832 is done. Its Phase 1 purpose was to characterize in-turn tool-loop
compaction and measure existing local prompt-debug/provider-body evidence
before any behavior change. It records that compaction is gated by
`state.iterations >= 3`, keeps the last two tool results verbatim, replaces
older tool results with char-count-only `[compacted:]` stubs, and does not
rehydrate elided content back into later prompts. Phase 1 does not prove a
measurable answer-quality regression, but it does show real artifact usage and
same-turn re-read proxy signals. Report hygiene recorded the exact artifact
scan script, original counts of 738 and 700, current hygiene rescan counts of
740 and 702, and the live-corpus reason for the slight drift.

The Wave 5 structural-decomposition closeout record is owner-ratified. It
closes the executor, core/tools cycle, `ToolCallLoop` engine,
`ToolCallExecutionStage` guard-chain, and highest-value `ToolCallSupport`
structural seams as a deliberate diminishing-returns decision. It explicitly
defers `LoopState`, `ExecutionOutcome`, remaining `ToolCallSupport` seams,
retry extraction, and compaction quality work to separately scoped tickets.

T833 is done. Its purpose was Wave 6 Tier 0 honest disclosure: update
Talos-owned README, AGENTS, and docs trust-surface claims to match current code
and add a docs honesty regression test. It does not authorize the five HIGH code
fixes, production `src/main` changes, capability work, compaction Phase 2,
candidate cut, or `site/` edits.

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
SCC. T821 completed the `SystemPromptBuilder` tool-catalog seam. T822
completed the final `RagService` / `ToolProtocolText` seam; local regenerated
architecture evidence after implementation shows `core -> tools = 0` and no
non-trivial top-level package SCCs. T823 completed `ToolCallLoop`
orchestration characterization. T824 completed behavior-preserving extraction
into package-private `dev.talos.runtime.ToolCallLoopEngine`. T825 completed
remaining `runtime.toolcall` internals scoping and selected T826
`ToolCallExecutionStage` characterization before any production decomposition.
T826 is done. T827 completed Qodana-summary evidence-order hardening. T828
completed the first production decomposition, focused on the pre-execution
guard chain behind the stable public stage surface. T829 completed
`ToolCallSupport` boundary scoping and selected native-call conversion as the
T830 extraction seam. T830 completed that behavior-preserving extraction.
T831 completed the next narrow `ToolCallSupport` seam: result formatting.
T832 completed Phase 1 evidence and characterization for in-turn compaction.
The Wave 5 structural-decomposition closeout is now owner-ratified. Future
`LoopState`, `ExecutionOutcome`, retry, remaining `ToolCallSupport`, or
compaction Phase 2 work must start from a new scoped ticket.

## Wave 6 Trust Track Status

T833 is complete. It records honest limits for anti-overclaim scope, secret
redaction, command-output handoff, Windows protected-path matching, model-host
locality, local secret encryption, and trace integrity. The tracked sanitized
evidence record is
`work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`;
the raw audit remains local and untracked by design.

Open Wave 6 high code-fix tickets:

- T836: Windows protected-path canonicalization.
- T837: `run_command` output handoff boundary.
- T838: master-key custody.

Completed Wave 6 high code-fix tickets:

- T834: strong redaction across model context and durable sinks.
- T835: chat transport localhost guard.
- T839: embedding host locality policy.

Trust tickets folded into the Wave 6 track for later re-scoping: T274, T276,
T281, T283, T286, T301, T319.

Capability tickets explicitly deferred and not the current Talos identity:
T294, T296, T299, T300, T302, T303, T304, T627.

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
