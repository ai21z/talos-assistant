---
wiki_schema: talos.wikiPage.v1
title: "Current Talos Engineering State"
kind: current-state
status: active
last_verified_commit: "420e6c92b1a5837caa1a1b4f2d79cacac6d9a165"
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
- Commit: `6c77d4b83f4d653a18beb94db2b33c8a244885a9`
- Talos version: `0.10.8`
- Note: branch and commit here identify the last generated evidence run tracked
  by the wiki. They are advisory metadata, not a claim that this Markdown file
  contains the SHA of its own containing commit.
- Active tickets: T923 (DPAPI raw-key transport design follow-up), T927
  (`/session clear` target mismatch), T937 (winget/signing policy), T950
  (private-document approval risk label), T951 (synchronized approval
  config-deny scoring), T952 (validator-grade PTY evidence capture), and T953
  (terminal fallback narrow no-break space glyph) remain open. T944 (public
  identity alignment), T946 (docs route coverage), T947 (docs
  renderer/fallback), T948 (direct `llama-server` acquisition guidance), T945
  (landing page copy/accessibility/render polish), and T949 (site E2E preview
  isolation) are done in the current 0.10.8 site/docs preparation batch. T936,
  T935,
  T933, T932, T934, T931, T930, T929, T926, T925, and T928 are done on
  `v0.9.0-beta-dev` and must be included in any future candidate/release merge.
- Active wave context: the v0.9.0-beta-dev line has been merged to public
  `main`; the branch retained for beta-dev continuity is now the 0.10.8
  candidate/staging line. T936 cut commit
  `420e6c92b1a5837caa1a1b4f2d79cacac6d9a165` promoted the accumulated
  `Unreleased` notes into `0.10.8`; later staging work produced QA staging
  artifacts, but public artifact publication remains blocked until the
  current QA/evidence follow-up tickets are fixed and the affected lanes are
  rerun.
- Known caveats: T923 remains a non-blocking custody-hardening design follow-up
  for the DPAPI raw-key PowerShell transport, T927 remains a `/session clear`
  UX mismatch, T937 remains the winget/signing policy follow-up, T950/T951/T952
  are pre-public QA evidence blockers, and T953 is a lower-priority terminal
  fallback polish follow-up. No public release, tag, winget publication, signed
  artifact, draft release, or history rewrite has happened.
- Next move: implement T952/T951/T950, then rerun only the affected T929 lanes
  before any public artifact decision. T953 should follow if time permits before
  staging.

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

## Architecture and Wave History (closed)

The Wave 5 structural-decomposition arc (T807-T832) is complete and the closeout
is owner-ratified in
`work-cycle-docs/reports/wave5-structural-decomposition-closeout-ratified.md`. It
established evidence-driven refactoring (T807-T810: the architecture-intelligence
report and the living-evidence wiki gate), decomposed `AssistantTurnExecutor`
(T811-T818), broke the `core <-> tools` package cycle (T819-T822), and decomposed
the tool-call loop and `ToolCallSupport` (T823-T832). Deferred follow-ups
(`LoopState`, `ExecutionOutcome`, remaining `ToolCallSupport` seams, retry
extraction, compaction Phase 2) require new scoped tickets.

The Wave 6 trust-surface arc (T833-T841) is complete. Tier 0 honest disclosure
(T833) bounded the README/AGENTS/docs trust claims to the audited wordings; the
five HIGH code fixes landed (T834 strong redaction, T835 chat-transport localhost
guard, T836 Windows protected-path canonicalization, T837 run_command output
handoff, T838 master-key custody, T839 embedding host locality), and the cheap
consolidation follow-ups closed (T840 protected-path realpath fail-closed, T841
competitor-claim honesty guard). The Ollama-independence arc (T855-T859) shipped
the managed-`llama.cpp` default with BM25-only retrieval and opt-in managed
embeddings, and T865 made the environment-flaky terminal tests deterministic so a
clean `check` is portably green on this host.

## Pre-release and Merge State

The pre-release arc (T842 onward) is done. The T842 owner-run full manual audit
held the trust surface with no hard-fail gate (findings ticketed T866-T873; T866,
T867, T868, T869, and T873 are fixed). T843-T846 (site honesty, RAG/beta docs, model/hardware-range evidence,
doctor diagnostics) and the T848-T854 correctness and diagnostic-truth fixes are
closed. The accepted beta range is one Windows machine, managed `llama.cpp`,
BM25-only retrieval; hybrid/vector retrieval is shipped but unvalidated.

The v0.10.6 main-merge plan on `improvement/qodana-cleanup`: step 1 (close 16
off-line tickets) and step 2 (cut the 0.10.6 candidate on the release branch) are
done; step 3.2 (resolve Qodana) is done with a committed Ultimate-scan baseline
and a clean security verdict; step 3.1 (the docs/report prune and CURRENT-STATE
condensation) is done (commit bb8b659a). The only remaining move is the merge into
main.

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

- The 86 baselined Qodana findings are accepted, not resolved; the deep
  (Ultimate) security verdict is clean but is a point-in-time scan.
- Hybrid/vector retrieval is shipped but unvalidated; the beta is honestly scoped
  to BM25-only.
- The remaining before-public-beta gates (one Linux Actions green run, identifier
  reconciliation, hybrid/vector validation, the T862/T301 release-doc pass) are
  tracked but not yet met.
