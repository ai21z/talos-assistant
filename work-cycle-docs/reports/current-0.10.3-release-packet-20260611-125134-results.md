# 0.10.3 Release Packet — Wave 2 Trust-Surface Integrity — 2026-06-11

Packet: `current-0.10.3-release-packet-20260611-125134`
Branch: `feature/wave2-trust-surface`
Cut commit: `a957d9f4f64e9478cec75925cc71355cdbdef74a` (scripted hermetic cut;
SHA tooling-sourced; see `build/reports/talos/candidate-manifest.json`).
Machine-checkable verdicts: sibling `-GATES.json` (schema
`talos.releaseGates.v1`).

## What this candidate contains

Wave 2 of the top-tier roadmap
(`work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md`),
tickets T754–T762, all in `tickets/done/` with completion evidence:

1. **T754** — bare tool-JSON regex hardened against catastrophic
   backtracking (possessive quantifiers, single owner, adversarial timeout
   regressions). Removed a latent DoS that ran on every model response.
2. **T755** — markdown-commentary sanitization moved BEFORE the approval
   gate: approved bytes == written bytes by construction, trace-recorded as
   `TOOL_CONTENT_SANITIZED`. The wave's headline doctrine fix.
3. **T756** — colored unified diff inside the approval window
   (java-diff-utils 4.12 — the wave's only new dependency; capped,
   redacted, fail-closed skips; byte-identical legacy detail;
   `APPROVAL_DIFF_PREVIEW` trace event; risk inference ignores diff bodies).
4. **T757** — mutation/checkpoint gating reads `ToolOperationMetadata`
   from the registry-resolved tool; hand name lists deleted; unknown tools
   fail CLOSED (`ToolMutationGate`); metadata/alias-policy parity pinned.
5. **T758** — typed `ToolFailureReason` codes end message-sniffing
   classification; producer prose deliberately byte-identical this wave;
   redaction now preserves reasons.
6. **T759** — single protected-path classifier with equals-or-suffix
   word-run matching (tokenizer.java-class false positives fixed;
   mysecrets.txt-class names kept); five divergent copies now delegate;
   protected-content policy v3 (RAG privacy partitions rebuild).
7. **T760** — blank answer ≠ refusal (truthful trace reasons); refusal
   markers scoped to the 240-char answer head (tail caveats no longer
   destroy grounded answers).
8. **T761** — advertised tool surface derived from `plan()` over a
   canonical descriptor catalog; advertised-vs-enforced drift eliminated.
9. **T762** — read-only proposal grounding is evidence-derived
   (any unread-file claim warns, not just the audit fixture's seven names);
   policy extracted from AssistantTurnExecutor per ownership doctrine.

## Gate status (see GATES.json for the authoritative ledger)

PASS (this session, deterministic):
- Scripted hermetic cut: bump → commit → installDist from the committed
  tree → launcher/SHA cross-check → mandatory post-bump `check`
  (unit 4,8xx + e2e + coverage floors 0.82/0.62 + per-package floors +
  canary + arch ratchet) → all four quality summaries report 0.10.3.
- Fresh native Qodana on the cut revision: provenance matches,
  **0 critical, 88 findings** — down from 169 at 0.10.2 (the T753-triaged
  noise families are baselined in qodana.yaml).

MANUAL_REQUIRED (owner steps — the local model backend was offline during
this session; doctrine forbids recording live verdicts without evidence):
- Sync-approval banks, both models (seed 424242). Rationale for rerun:
  T758 kept all producer prose byte-identical and T756 is strictly
  additive, but the approval-window content grew and capability-frame
  fallbacks changed for ctx-less paths (T761) — confirm no model-behavior
  regression. Watch the 5/31 bounded-rescue scenarios from the 0.10.2
  Qwen bank (open NAMED-choice first-attempt investigation).
- Safe-redirected talosbench banks, both models.
- Capability/private-mode bank: T759 changed protected-path
  classification — verify no protected-read approval regressions.
- TRUE_PTY manual cycle: REQUIRED, not carryable from 0.10.1/0.10.2 —
  the approval window now renders the diff block. Chrome strings and the
  `Allow? [y=yes, a=yes for session, N=no]` prompt are byte-identical;
  the cycle confirms real-terminal rendering (color + NO_COLOR/ASCII).
- Packet-artifacts canary scan (runs over the live-lane artifact roots).

## Self-distrust notes

- The blank-answer guard branch (T760) is pinned at guard-unit level only:
  executor-level blank loop answers are intercepted by the loop-summary
  fallback first (pre-existing ordering, out of W2.5 scope).
- Known residuals carried forward, documented in the tickets:
  `ExpectedTargetScopeRepairPlanner` still parses FailureDecision free
  text (needs Wave-5 structured OutcomeSignals); the bare
  `ToolDescriptor(name, description)` constructor defaults to READ_ONLY
  metadata (registration-side fail-open, mitigated by the golden-row
  registry coverage rule); literal `Token.java` lexer files remain
  path-protected (source-extension exemption deliberately out of scope).
- One non-wave fix rode along: `LlmClientSamplingConfigTest` was reading
  the developer's real `~/.talos/config.yaml` (T745 seeded sampling block)
  and failing the whole test task on this host; it is now hermetic.
