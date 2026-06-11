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

**Every lane is green for both audited models** (live lanes completed
2026-06-11 after the cut; verdicts recorded the same day).

| Lane | GPT-OSS | Qwen | Verdict |
|---|---|---|---|
| `SAFE_REDIRECTED_STDIN` | 19 PASS / 22 MANUAL_REQUIRED, 0 FAIL | 19 PASS / 22 MANUAL_REQUIRED, 0 FAIL | PASS (matches 0.10.2 baseline) |
| `SYNC_APPROVAL` (full 31-scenario live bank, seed 424242) | 31/31, artifact scan PASS, 2 PASS_WITH_RUNTIME_REPAIR | 31/31, artifact scan PASS, **0 ladder rescues** (down from 5 at 0.10.2) | PASS |
| `CAPABILITY_PRIVATE_MODE` | 24 prompts | 24 prompts | PASS — 48/48 runs, 0 secret/canary leaks, 0 overclaims; no protected-read approval regressions under the T759 classifier |
| `TRUE_PTY_MANUAL` | — | fresh owner real-terminal run: validator + canary PASS; **diff-bearing approval window rendered cleanly** (`diff (+1 -1)`, `@@` hunk, checkpoint CREATED, verification PASSED) | PASS |
| Canary scans | packet artifacts root (no allowlist) | pty + capability roots (fixture allowlists) | PASS |
| Deterministic summaries + Qodana | all summaries 0.10.3; fresh native scan, 0 critical, 88 findings (169 → 88 vs 0.10.2) | (bundle) | PASS |

Notes and honest deltas:
- Qwen sync bank had ZERO bounded-T743 rescues this time (5/31 at 0.10.2)
  — the NAMED-choice first-attempt anomaly did not reproduce on this
  candidate. GPT-OSS picked up 2 PASS_WITH_RUNTIME_REPAIR scenarios
  (0 at 0.10.2) — both verified-final, honestly recorded.
- The 4 Qwen workspace-op scenarios score PASS with BLOCKED traces exactly
  as at 0.10.2: a pre-existing TaskContractResolver quirk extracts a bogus
  expected target ("by") from the prompt; the operation is applied and
  checkpointed, then the turn fail-closes on the phantom remaining target.
  Pre-existing, not a wave-2 delta; ticket candidate for the next wave.
- Three aborted/empty Qwen+GPT-OSS talosbench run dirs (zero files) were
  removed before the real runs: the initial runbook wrapped the runner in
  Windows PowerShell 5.1, whose stderr handling aborts it at the first
  case. Corrected in the packet OWNER-RUNBOOK (run from pwsh directly).
- The PTY validator's result vocabulary is `"status": "PASSED"` (not
  "PASS") — runbook corrected after one fail-closed validation round.

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
