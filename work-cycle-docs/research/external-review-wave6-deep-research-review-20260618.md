# independent review Wave 6 Deep-Research Review Capture (2026-06-18)

Status: captured secondary review
Workflow: `w352woggx`
Branch context: `v0.9.0-beta-dev`
Talos version context: `0.10.5`

## Scope Boundary

This file captures the independent review review pasted into the working session after the
deep-research workflow completed. It is not the full deep-research report and
must not be treated as primary source evidence by itself.

Use this as a secondary review artifact that records positioning constraints,
review conclusions, and follow-up ticket direction. For external claims, prefer
the existing sourced audit in
`work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`
unless the full `w352woggx` report is later saved.

## Captured Review

independent review reviewed the completed deep-research workflow and judged it high quality
with three cautions:

- It did not verify Talos code, so every "credible if implemented" finding
  depends on planned fixes rather than shipped behavior.
- Differentiation was evidenced against local coding assistant and Aider only; do not
  publish unqualified "no competitor does this" claims until Gemini CLI, Cline,
  Goose, Continue, OpenCode, and other relevant tools are checked.
- The audience verdict is a medium-confidence hypothesis, not settled evidence.

The review said the direction holds:

- Tool-use hallucination and attribution remain real problems, and weak local
  models need a non-model governance layer.
- Talos's anti-overclaim direction is differentiated when scoped to runtime
  checks that verify whether a file-mutation action actually happened.
- The high-priority fixes have recognizable best-practice bars: stronger
  secret detection/redaction, local endpoint enforcement, Windows path
  canonicalization, command-output handoff controls, and better key custody.

The review also recorded five wording bounds that must survive public copy:

1. The AgentHallu `11.6%` figure is a step-localization/attribution number for
   a tool-use sub-category. Do not present it as detection accuracy, a general
   unsolved-tool-use claim, or proof of Talos's solution.
2. The literature motivates the problem; it does not prescribe Talos's exact
   mechanism.
3. Talos's deterministic guarantee is partial. Current disclosure should stay
   scoped to file-mutation/no-success correction, not `run_command` claims or
   factual read/answer claims.
4. Local traces are durable evidence artifacts, not tamper-evident or
   tamper-proof logs. Do not call them provable until signed, hash-chained, or
   Merkle-backed integrity exists.
5. OWASP-style wording should match its gradation: avoid overclaiming "forbid"
   when the source says to avoid or discourage a practice.

## Code-Check Correction

independent review's code cross-check correction is load-bearing:

- Talos currently preserves an approved-bytes == written-bytes invariant by
  sanitizing once before approval and executing the approved call shape.
- Talos does not yet perform a post-write readback/re-hash round trip in the
  write/edit paths.
- Therefore, "approved bytes == written bytes" remains an ordering/invariant
  claim, not a cryptographic or post-write proof.

Public positioning must not use:

- "provable agent"
- "makes the model provable"
- "tamper-proof"
- unqualified "no competitor" claims
- unqualified claims that the literature prescribes Talos's mechanism

Preferred bounded positioning:

> the local assistant that will not lie about the file changes it made

Architecture wording may use "auditable operator" only when scoped to the
file-mutation anti-overclaim behavior that exists today.

## Wave 6 Follow-Up Direction

T833 remains correct as a Tier 0 honest-disclosure pass. The follow-up path is
T834-T838:

- T834: strong redaction across model context and durable sinks.
- T835: chat transport localhost guard.
- T836: Windows protected-path canonicalization.
- T837: `run_command` output handoff boundary.
- T838: master-key custody.

Do not publish stronger trust positioning until those code fixes land or the
public copy remains explicitly bounded to the current disclosure language.
