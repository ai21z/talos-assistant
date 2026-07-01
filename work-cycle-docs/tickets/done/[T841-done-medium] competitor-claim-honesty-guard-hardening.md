# [T841-done-medium] Competitor Claim Honesty Guard Hardening

Status: done
Priority: medium
Type: docs-honesty-test
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Broaden the tracked public-pitch honesty guard so Talos-owned README, AGENTS,
and `docs/**` surfaces cannot silently introduce unqualified competitor
exclusivity claims such as "only assistant", "no other agent", "nobody else",
or "unlike every competitor".

This keeps the local competitor-matrix evidence local-only while still
protecting tracked public docs from over-broad publish wording.

## Scope

- Extend `TrustClaimsHonestyTest` to catch broader unqualified competitor
  exclusivity phrases, not just exact `no competitor`.
- Add a pattern self-test that proves the guard catches unsafe phrases and
  permits bounded evidence wording such as "No inspected source-level tool had
  the same post-apply gate."
- Keep the guard scoped to `README.md`, `AGENTS.md`, and `docs/**`.
- Do not promote local competitor-matrix research into tracked repo files.
- Do not edit or stage `site/`.

## Acceptance Criteria

- Focused `TrustClaimsHonestyTest` fails before the broader guard and passes
  after the regex hardening.
- Existing bounded trust-disclosure assertions remain green.
- The pattern does not false-positive on ordinary wording such as
  `read-only tool surface` or `Talos is not the only tool with deterministic
  safety checks`.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passes.
- `git diff --check -- . ':!site'` passes.
- No `src/main`, `site/`, local competitor report, release metadata, Qodana
  policy, or candidate artifact changes are included.

## Completion Evidence

- Red-first focused run:
  `.\gradlew.bat test --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon`
  failed in `competitorExclusivityGuardCatchesBroaderUnqualifiedClaims()`
  before the regex was broadened.
- Focused green run passed after the guard was hardened.
- The implementation is test-only plus ticket/wiki ledger updates.

## Non-Goals

- Do not claim competitor evidence at 100% confidence.
- Do not track or publish `local/marketing/competitor-trust-matrix.md` or
  `local/marketing/competitor-absence-hardening.md`.
- Do not update `site/`; site copy remains owner-managed work.
- Do not cut or recut a beta candidate.
