# T95 - Static Web Expected-Target Repair Framing

Status: Open
Priority: Medium
Branch: v0.9.0-beta-dev
Source: Clean Qwen/GPT-OSS audit follow-up

## Evidence Summary

- Source: clean two-model manual audit
- Date: 2026-05-03
- Models:
  - Qwen: `ollama/qwen2.5-coder:14b`
  - GPT-OSS: `ollama/gpt-oss:20b`
- Audit root: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152`
- Raw transcript: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/TEST-OUTPUT-QWEN-14B.txt`
- Comparison transcript:
  `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/TEST-OUTPUT-GPT-OSS-20B.txt`
- Findings: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/FINDINGS-CLEAN-TWO-MODEL.md`

Observed evidence:

- Qwen first BMI create mutated only `script.js` while expected targets were
  `index.html`, `styles.css`, and `scripts.js`.
- Qwen later still failed static verification.
- GPT-OSS passed the same BMI task, proving the verifier can validate the
  desired result.

## Classification

Primary taxonomy bucket: `REPAIR_CONTROL`

Secondary buckets:

- `VERIFICATION`
- `CURRENT_TURN_FRAME`
- `MODEL_COMPETENCE`

Blocker level: candidate follow-up

Why this level:

The verifier correctly catches wrong-target mutation, but repair/current-turn
framing needs to make missing expected targets explicit. Qwen confused
`script.js` and `scripts.js`; Talos must not accept that as task completion.

## Architectural Hypothesis

Static verification knows expected targets and changed files, but the repair
frame may not present missing expected targets strongly enough after a
wrong-target mutation. The runtime-owned changed-files summary should stay
authoritative while repair framing names the expected target that was not
mutated.

Likely code/document areas:

- `src/main/kotlin/dev/talos/runtime/verification/StaticTaskVerifier.kt`
- static verification result or repair prompt framing
- assistant turn executor repair context tests

## Goal

Improve repair/current-turn framing when static web verification reports
expected targets were not mutated. Similar filenames such as `script.js` and
`scripts.js` must be distinguished, and wrong-target mutation must not be
accepted as task completion.

## Non-Goals

- No deterministic static web app generator.
- No broad model-specific special casing for Qwen.
- No regression to the GPT-OSS passing path.
- No full T61-style audit as part of this individual ticket.

## Implementation Notes

Tests should cover expected target `scripts.js` not being mutated when
`script.js` exists. The repair frame should name missing expected targets
explicitly and, when useful, call out similar wrong targets as not satisfying
the request.

## Acceptance Criteria

- Tests cover expected target `scripts.js` not being mutated when `script.js`
  exists.
- Repair framing names missing expected targets explicitly.
- Changed-files summary remains runtime-owned and accurate.
- Wrong-target mutation is not accepted as task completion.
- No regression to GPT-OSS passing path.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Static web verification or repair-context test where expected target
  `scripts.js` is missing from successful mutations while stale `script.js`
  exists.
- Assertion: repair framing names `scripts.js` explicitly and does not treat
  `script.js` as a substitute.

Commands:

```powershell
./gradlew.bat test --tests "*StaticTaskVerifierTest*" --no-daemon
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop for T95.
- Do not run the clean two-model milestone audit after this ticket alone.
- Re-run the clean Qwen/GPT-OSS audit after the T93-T95 batch passes normal
  verification.

## Known Risks

- Repair framing can become too verbose if it repeats the full verifier report.
- Filename similarity warnings should help the model choose the current target,
  not become a global fuzzy-matching policy.

## Known Follow-Ups

- T96 README proposal apply strategy hardening remains optional and should only
  be opened or implemented after T93-T95 unless it falls naturally out of the
  same code path.

