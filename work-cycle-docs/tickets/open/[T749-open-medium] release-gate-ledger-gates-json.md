# T749 - Release Gate Ledger (GATES.json)

Status: open
Severity: medium
Release gate: no (makes future gates machine-checkable)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

Release-gate verdicts live only in prose reports; "the lane passed" is an
asserted markdown sentence judged by reading. The build side already has
machine-readable, provenance-aware summaries (version/coverage/e2e/qodana
JSON), but the live-audit side has nothing equivalent — which is how a wrong
SHA and an overstated lane claim survived in the 0.10.1 packet until a manual
forensic pass caught them.

## Evidence Analysis

- The quality-infrastructure evaluation flagged this exact asymmetry: scripted
  e2e gates emit `build/reports/talos/*.json` with provenance fields and
  self-flagged staleness (qodana-summary's `revisionStatus`), while live-audit
  results are "hand-written ticket markdown, not machine-checkable gate
  artifacts."
- Defects this would have caught structurally: the invalid full SHA (report
  line 5, fixed in `953bf4eb`) — a ledger whose `sha` field comes from
  tooling cannot be hand-mistyped; the T313 closure-wording overstatement —
  a `TRUE_PTY_MANUAL: MANUAL_REQUIRED` ledger row contradicts prose claims
  mechanically.
- House JSON pattern to reuse: `writeSummarySoft`/`writeJson`
  (build.gradle.kts:31-44, 112-120) if generated from Gradle; v1 may be
  hand-authored but schema-validated.
- House docs-test pattern for the validator: ReadmePrivacyCopyTest style.

## Architectural Hypothesis

A minimal, schema-validated per-packet `GATES.json` gives every release packet
a machine-checkable verdict layer without changing how lanes run; generation
can be automated later (the schema is the contract).

## Architecture Metadata

Capability: release evidence formalization
Operation(s): n/a (docs/schema + validation test)
Owning package/class: `work-cycle-docs/release-gate-ledger.md` (schema doc,
new), `dev.talos.docs.GatesLedgerTest` (new)
New or changed tools: none
Risk, approval, and protected paths: n/a
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: n/a
Refactor scope: new schema doc + new test + one exemplar JSON

## Required Behavior

1. Schema doc `work-cycle-docs/release-gate-ledger.md` defining GATES.json v1:
   top-level `{schema, packet, branch, sha, generated, gates:[...]}`; each
   gate: `{name, lane, status (PASS|FAIL_REVIEW_REQUIRED|MANUAL_REQUIRED|
   NOT_RUN|SUPERSEDED), evidencePath, model?, notes?, expires?}`.
   Rule: `sha` MUST be captured from tooling, never typed.
2. Exemplar: author `work-cycle-docs/reports/current-0.10.1-release-packet-20260610-090049-GATES.json`
   retrofitting the (now corrected) 0.10.1 packet verdicts — safe lanes PASS,
   sync GPT-OSS PASS, sync Qwen FAIL_REVIEW_REQUIRED, workspace-ops PASS,
   TRUE_PTY_MANUAL PASS, capability PASS-heuristic — with evidence paths.
3. `GatesLedgerTest`: discovers `work-cycle-docs/reports/**/*GATES.json`,
   validates required fields, status vocabulary, `sha` is 40-hex, evidence
   paths non-blank; clear per-file failure messages.
4. Wave close (0.10.2 packet) authors its GATES.json under the same schema.

## Non-Goals

- No automatic generation from harness runs in v1 (future: cut-candidate or
  the audit runners emit rows).
- No changes to existing prose reports (the ledger complements, not replaces).

## Tests

- `GatesLedgerTest` green against the exemplar; deliberate-violation fixture
  exercised during development.

## Acceptance Criteria

- Schema doc + exemplar + test committed;
  `./gradlew.bat test --tests "dev.talos.docs.GatesLedgerTest" --no-daemon`
  green.
- 0.10.2 wave-close packet ships a valid GATES.json.
- CHANGELOG `## [Unreleased]` gains a T749 entry.
