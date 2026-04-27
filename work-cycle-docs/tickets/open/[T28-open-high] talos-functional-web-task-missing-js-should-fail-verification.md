# [T28-open-high] Ticket: Functional Web Task Missing JS Should Fail Verification
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- work-cycle-docs/new-work.md
- docs/new-architecture/talos-harness-source-of-truth.md
- docs/new-architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T15-done-high] talos-readback-verification-wording.md
- work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md

## Why This Ticket Exists

The static verifier correctly catches incoherent three-file web apps. Manual testing found a gap for functional web tasks where Talos only creates or edits HTML/CSS and never creates JavaScript. The verifier can report that web coherence is unavailable instead of failing the task with concrete missing-functionality problems.

For a regular user asking for a working BMI calculator, `no task-specific verifier applicable` or `web coherence unavailable` is too weak.

## Problem

Reproduced transcript:

- `local/manual-testing/deep-review-2/nondev-bmi-title-only-transcript.txt`

Observed:

1. Talos updated only `index.html` for a request to make a working BMI calculator.
2. Final answer included:

```text
[File write/readback passed. No task-specific verifier was applicable, so task completion was not verified.]
```

3. Later partial repair produced:

```text
[Partial verification: static checks failed - web coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.]
```

Final files:

- `index.html` contained duplicate `weight`, `height`, and `result` IDs.
- No calculate button.
- No `scripts.js`.
- No JavaScript link.

For the user request, the deterministic result should be task incomplete with concrete missing elements, not merely readback-only or unavailable coherence.

## Goal

When the user asks for a functional calculator/web page, missing JavaScript/linkage/control elements should fail static verification with actionable problems even if the workspace does not yet expose a complete HTML/CSS/JS surface.

## Scope

In scope:
- Detect functional web-app/calculator task intent from `TaskContract`.
- If mutation touched web targets but required JS/control/linkage is absent, produce `FAILED` or `PARTIAL` static verification with concrete problems.
- Catch duplicate IDs relevant to form/calculator tasks.

Out of scope:
- Browser execution.
- General JS semantic correctness.
- Large framework/app analysis.

## Proposed Work

- Extend `StaticTaskVerifier` web verifier selection so calculator/functionality requests do not require all three file types before applying task-specific checks.
- Add checks for:
  - missing script file or inline script when functionality is requested,
  - missing script reference,
  - missing button or submit control,
  - duplicate IDs for expected controls/results.
- Keep wording honest: this is static verification, not browser/runtime proof.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for functional calculator task with:
  - only HTML/CSS present,
  - missing `scripts.js`,
  - duplicate IDs,
  - no calculate button.
- E2E scenario matching non-technical BMI prompt where Talos mutates only `index.html`.
- Manual Talos check in title-only BMI workspace.

## Acceptance Criteria

- Functional BMI/web task with no JS does not report readback-only as sufficient.
- Verifier returns actionable missing-JS/control problems.
- Duplicate expected IDs are detected.
- Final answer does not imply task completion.
- Focused tests and e2e pass.

## Evidence

Manual deep-review result on 2026-04-28:

- `nondev-bmi-title-only-transcript.txt` shows Talos partially editing HTML for a functional BMI calculator while verifier reported no applicable task-specific verifier or unavailable web coherence.
