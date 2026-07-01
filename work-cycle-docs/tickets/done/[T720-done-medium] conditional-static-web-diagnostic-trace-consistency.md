# T720 - Conditional Static-Web Diagnostic Trace Consistency

Status: done
Priority: medium
Created: 2026-06-07
Completed: 2026-06-07
Branch: v0.9.0-beta-dev

## Problem

Prompt P21 in `current-two-model-audit-20260607-204059` was a conditional
review-and-fix prompt:

```text
Review the BMI calculator you just created and fix any obvious issue that would
stop it from working in a browser.
```

Both Qwen and GPT-OSS read the relevant static files and correctly made no
mutation, but the deterministic final answer said "Runtime static verification
found no obvious..." while `/last trace` recorded `COMPLETED_UNVERIFIED`,
`verifierProfile: NONE_OR_NOT_DERIVED`, and `Verification: NOT_RUN`.

This is a runtime UX / trace-truthfulness consistency defect. It is not a false
mutation success because no mutation occurred.

## Evidence

- Finding report:
  `local/manual-testing/current-two-model-audit-20260607-204059/FINDINGS.md`
- Qwen trace:
  `local/manual-testing/current-two-model-audit-20260607-204059/artifacts/qwen/traces/P21-last-trace.txt`
- GPT-OSS trace:
  `local/manual-testing/current-two-model-audit-20260607-204059/artifacts/gptoss/traces/P21-last-trace.txt`
- Source wording:
  `src/main/java/dev/talos/runtime/policy/ConditionalReviewFixPolicy.java`

## Goal

Keep the correct no-change behavior, but make final deterministic wording match
the trace semantics: this is diagnostic inspection evidence, not post-apply
verification.

## Non-Goals

- Do not label the turn `COMPLETED_VERIFIED`.
- Do not add browser/render proof.
- Do not change conditional review/fix mutation behavior.
- Do not change static-web verifier profiles.

## Architecture Metadata

Capability:

- Static-web conditional review/fix

Operation(s):

- inspect
- conditionally mutate

Owning package/class:

- `dev.talos.runtime.policy.ConditionalReviewFixPolicy`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: trace/final truthfulness
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: read relevant static files
- Verification profile: unchanged; no post-apply verifier runs when no mutation
  occurs
- Repair profile: unchanged

Outcome and trace:

- Keep `SATISFIED_BY_INSPECTION` action-obligation evidence.
- Keep `Verification: NOT_RUN` for no-mutation/no-post-apply-verifier turns.
- Change final answer wording to diagnostic inspection.

Refactor scope:

- Allowed: final deterministic wording and focused tests.
- Forbidden: trace schema expansion unless necessary.

## Acceptance Criteria

- Passing conditional review/fix answer contains "No file change was needed" and
  "diagnostic inspection" wording.
- Passing conditional review/fix answer does not say "Runtime static
  verification found..." when `/last trace` will say post-apply verification was
  not run.
- Trace still records `ACTION_OBLIGATION_EVALUATED` with
  `SATISFIED_BY_INSPECTION`.
- Trace still records `Verification: NOT_RUN` for inspection-only no-change
  turns.
- Existing repair-needed and mutation paths remain unchanged.

## Tests / Evidence

Required tests:

- `dev.talos.cli.modes.AssistantTurnExecutorTest`

Required commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- Move to done only after focused tests, full `check`, `git diff --check`, and
  focused installed-product audit evidence.

## Completion Evidence

Implemented:

- Changed deterministic no-change wording from "Runtime static verification
  found..." to "Runtime static diagnostic inspection found...".
- Changed checked-file wording to "Diagnostic inspection checked files...".
- Kept trace/outcome semantics unchanged: no-mutation inspection-only turns still
  report post-apply verification as `NOT_RUN`.
- Kept `SATISFIED_BY_INSPECTION` action-obligation evidence.

Verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
.\gradlew.bat installDist --no-daemon
```

Focused installed-product audit:

- `local/manual-testing/t719-t720-focused-p21-audit-20260607-220219/FOCUSED-AUDIT.md`
- GPT-OSS P21 path: diagnostic wording present, old verification wording absent,
  `SATISFIED_BY_INSPECTION` present, `Verification: NOT_RUN` present.
- Qwen explicit-read path: diagnostic wording present, old verification wording
  absent, `SATISFIED_BY_INSPECTION` present, `Verification: NOT_RUN` present.
- Fresh Qwen without creation history did not exercise this no-change path; it
  attempted an invalid `bmi_calculator.html` edit and runtime blocked it before
  approval. That is separate model/tool-loop convergence evidence, not a T720
  wording regression.

