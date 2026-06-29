---
wiki_schema: talos.wikiPage.v1
title: "Current Talos Engineering State"
kind: current-state
status: active
last_verified_commit: "b6ca07a9ffe0ab372a34bbfd90207b1927dc7340"
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

- Branch: `improvement/qodana-cleanup`
- Commit: `b6ca07a9ffe0ab372a34bbfd90207b1927dc7340`
- Talos version: `0.10.6`
- Note: branch and commit here identify the last generated evidence run tracked
  by the wiki. They are advisory metadata, not a claim that this Markdown file
  contains the SHA of its own containing commit.
- Active tickets: ten open live-findings tickets carried into the main-merge
  line. Eight trust/behaviour findings: T862 (Maven workspace-profile docs), T867
  (protected-path alias classification), T868 (private-mode retrieve tool-surface
  narrowing), T869 (failed/denied mutation outcome label), T870 (redacted-search
  rendering), T871 (qwen grounding / edit-shape steering), T872 (run_command +
  batch coverage reprobe), and T873 (broader command-output truthfulness shapes).
  Two deferred product/CLI findings: T885 (terminal UI for verification
  profiles) and T886 (configure/test/guide supported models). T866
  (git-status fabrication), T908 (Plan
  read-only target/progress obligations), T909 (protected-read session approval
  UI), T913 (Agent command fallback + false approval-denial claim), T914
  (ignored-instruction output target role projection), T912 (Plan static-web
  evidence-bound diagnostics), T915 (read-only prompt capability advertising),
  T916 (read-only command refusal no-inspection), T911 (read-only no-tool
  manifest evidence mismatch), T917 (static-web verifier CSS requirement for
  JS-only fixes), T910 (zero changed-files false mutation warning), T897
  (static-web redesign inferred satellites), T907 (`/last trace` canonical
  mode rendering), T906 (shell-command hint short-prose false positives), and
  T905 (set-model alias guidance) are done; the T834 mutation anti-overclaim
  path is done.
- Active wave context: the Wave 5 structural-decomposition arc (T807-T832) and
  the Wave 6 trust-surface arc (T833-T841) are both closed and owner-ratified.
  The current phase is the v0.10.6 main-merge preparation on
  `improvement/qodana-cleanup`: the 0.10.6 candidate is cut, the marketing site
  is refreshed, and the Qodana cleanup (merge-plan step 3.2) is done -- 315 to 86
  findings via behaviour-preserving fixes plus a committed Ultimate-scan baseline,
  with the deep security scan clean.
- Known caveats: the 86 residual Qodana findings are triaged and accepted via
  `qodana-baseline.sarif.json` (style nits, intentional/trust-surface guards,
  advisory refactors, and one non-exploitable taint flow); scans now gate on NEW
  only. T807 generated reports remain ignored build evidence, Qodana stays a
  read-only input for architecture reporting, and the wiki evidence-liveness lint
  is limited to generated JSON report claims. The branch is local-only; no push.
- Next move: merge-plan step 3.1 (the docs/report prune and this page's
  condensation) is done, and the 2026-06-26 manual-testing findings are captured as
  tickets T874-T880. The only remaining move is to merge
  `improvement/qodana-cleanup` into main as the v0.10.6 line -- a deliberate
  non-fast-forward merge, since main carries 4 governance commits the branch lacks.

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
held the trust surface with no hard-fail gate (findings ticketed T866-T873; T866
is fixed). T843-T846 (site honesty, RAG/beta docs, model/hardware-range evidence,
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
