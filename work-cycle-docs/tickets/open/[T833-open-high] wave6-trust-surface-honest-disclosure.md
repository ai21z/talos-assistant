# [T833-open-high] Wave 6 trust-surface honest disclosure

Status: open
Priority: high
Type: docs-and-test
Branch: `v0.9.0-beta-dev`
Base commit: `f8e8c3065ff60d706d8342fda89101f834727cef`
Talos version: `0.10.5`

## Purpose

T833 is the Wave 6 Tier 0 honesty pass. It updates Talos-owned
trust/privacy/security/truthfulness claims so public and maintainer-facing docs
match current code rather than intended future code.

Tracked source of truth:
`work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`.

The larger raw audit remains local research material and is not required for a
clean checkout or active ticket review.

## Scope

- Bound trust-surface claims in `README.md`, `AGENTS.md`, and `docs/**`.
- Add a docs honesty regression test that pins the bounded language.
- Add a report with Wave 6 trust-track triage and site-copy recommendations.
- Leave this ticket open for review.

## Required Disclosures

- Talos's deterministic no-change/no-success correction is strongest for
  file-mutation turns; `run_command` claims and read/answer factual claims are
  not yet equivalently covered.
- Secret redaction currently catches common key=value secret shapes and known
  canaries; it does not yet detect standalone API tokens, JWTs, PEM
  private-key blocks, connection strings, or high-entropy blobs.
- `run_command` stdout and stderr are not withheld from model context by
  default.
- On Windows, paths that differ only by trailing dots or spaces can bypass
  exact-name protected-path matching.
- The chat transport does not yet enforce a localhost-only guard; a configured
  remote `ollama.host` can receive prompts.
- The local master key is still stored beside the encrypted data, so current
  encryption is casual-inspection protection, not OS-backed key custody.
- Local traces and logs are durable evidence artifacts, but they are not
  tamper-evident.

## Non-Goals

T833 does not authorize:

- the five HIGH code fixes from the audit;
- any production `src/main` change;
- candidate cut or release metadata update;
- compaction Phase 2;
- OCR, PowerPoint, RAG breadth, static-web browser, or other capability work;
- any `site/` edit or staging.

## Wave 6 Triage

Wave 6 trust track, to be re-scoped against current code by later tickets:
T274, T276, T281, T283, T286, T301, T319.

Capability backlog, explicitly deferred and not the product identity:
T294, T296, T299, T300, T302, T303, T304, T627.

## Post-Review Capture

The Opus review of deep-research workflow `w352woggx` is captured as a
secondary review artifact at
`work-cycle-docs/research/opus-wave6-deep-research-review-20260618.md`. It is
not the full deep-research report.

T833 remains correct as the Tier 0 disclosure pass. The code-fix path before
public push is T834-T838:

- T834: strong redaction across model context and durable sinks.
- T835: chat transport localhost guard.
- T836: Windows protected-path canonicalization.
- T837: `run_command` output handoff boundary.
- T838: master-key custody.

Do not edit or stage `site/` in this pass. Do not use "provable agent",
"makes the model provable", "tamper-proof", or unqualified competitor claims in
public positioning.

## Verification Plan

```powershell
.\gradlew.bat test --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
git status --short -- . ':!site'
```

## Review State

T833 remains open after implementation for owner review. Any code-fix follow-up
starts at T834+ with a separate scoped ticket.
