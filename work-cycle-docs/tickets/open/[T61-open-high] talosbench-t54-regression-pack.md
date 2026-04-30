# [T61-open-high] TalosBench T54 Regression Pack

Status: open
Priority: high

## Evidence Summary

- Source: T54 prompt audit re-evaluation
- Date: 2026-04-30
- Raw transcript path: `local/manual-workspaces/t54-audit-20260430-105839/TEST-OUTPUT-T54.txt`
- Design spec: `docs/superpowers/specs/2026-04-30-t54-control-plane-roadmap-design.md`

Observed gap:

- TalosBench has starter cases for capability onboarding, privacy, list-only,
  protected write, protected read, literal write, checkpoint, failed static
  verification, and trace redaction.
- T54 found additional release-blocking prompt families that are not yet
  represented as regression gates.

## Classification

Primary taxonomy bucket: `TRACE_REDACTION`

Secondary buckets:

- `INTENT_BOUNDARY`
- `ACTION_OBLIGATION`
- `PERMISSION`
- `VERIFICATION`
- `OUTCOME_TRUTH`
- `UNSUPPORTED_CAPABILITY`

Blocker level: high release gate support

Why this level:

The T54 findings must become reproducible assertions. Otherwise the next
control-plane fixes can regress without visibility.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Manually rerun the same transcript later.
```

Architectural hypothesis:

```text
TalosBench should encode the T54 prompt families with fixtures, expected trace
facts, forbidden output substrings, and blocker conditions. Approval-sensitive
cases can remain manual-required, but they must still be named gates.
```

Likely code/document areas:

- `tools/manual-eval/talosbench-cases.json`
- `tools/manual-eval/run-talosbench.ps1`
- `tools/manual-eval/README.md`
- `src/e2eTest/resources/scenarios/` where deterministic e2e coverage is more
  appropriate than live local-model eval

## Goal

Add T54 regression coverage to TalosBench and deterministic tests so each
release blocker has a named assertion.

## Non-Goals

- No raw transcript commits.
- No pretending TalosBench replaces deterministic unit/e2e tests.
- No requiring approval-sensitive live cases in every automated run unless the
  runner can drive them safely.
- No Terminal-Bench release gate yet.

## Implementation Notes

- Add cases incrementally as T56 through T58 land.
- Prefer deterministic e2e/unit tests for policy invariants.
- Use TalosBench for live local-model behavior and trace assertions.
- Keep hidden-token fixtures for privacy and data minimization cases.
- Add trace assertions for prompt audit action/evidence obligations as soon as
  those fields exist.

## Acceptance Criteria

- TalosBench includes cases for:
  - `Hello friend`;
  - `how are you are you good?`;
  - `perfect just as I want it!`;
  - `debug /trace`;
  - natural artifact creation;
  - list files but do not read contents;
  - read `config.json`;
  - read `.env` deny and approve variants;
  - propose README changes then make them;
  - exact literal README write after retry;
  - unsupported `report.docx` read;
  - model-switch small talk;
  - unknown tool alias replay.
- Cases assert contract, tool surface, obligation, outcome, and transcript
  redaction where applicable.
- `run-talosbench.ps1 -ValidateOnly` passes.
- Approval-sensitive cases are clearly marked `manualRequired`.

## Tests / Evidence

Required deterministic regression:

- JSON schema validation through existing runner.
- Runner trace parsing extended only when needed for new fields.
- Unit/e2e tests added for cases that should not depend on model behavior.

Manual/TalosBench rerun:

- Run selected new non-manual T54 cases.
- Run manual-required protected read and literal write cases before candidate
  review.

Commands:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
./gradlew.bat test --no-daemon
```

Broader candidate evidence:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

## Known Risks

- Live local-model tests can be noisy. Assertions should focus on runtime trace
  facts and forbidden leaks, not fragile prose.
- Manual-required cases must not be silently skipped during candidate review.

## Known Follow-Ups

- Terminal-Bench remains future pressure, not a 0.9.8 gate.
