# [open] Ticket: Generic Web-App Static Verifier v0
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/talos-static-verifier-web-app-scope-and-wording.md`
- `work-cycle-docs/tickets/done/talos-read-only-web-diagnostics-static-grounding.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

The final manual-test workspace was not a functioning BMI calculator:

- `index.html` had no form, inputs, button, or script tag.
- `scripts.js` contained only placeholder text.
- `styles.css` contained useful form styles that the HTML did not use.

Yet some turns reported readback/static success because the verifier only knew
that a target file existed and was readable.

## Problem

Talos has early web coherence checks, but they are not strong enough for a
basic multi-file web-app task. A user asking for a functioning web app expects
the HTML, CSS, and JavaScript to be connected and non-placeholder, not merely
present on disk.

## Goal

Add a generic static web-app verifier v0. It should not be BMI-specific by
default, but it should catch obvious HTML/CSS/JS wiring failures for small local
web workspaces.

## Scope

### In scope

- Check expected web files exist when a web-app task names or implies them.
- Check `index.html` links CSS files that exist.
- Check `index.html` links JavaScript files that exist.
- Flag duplicate stylesheet/script references.
- Flag placeholder or near-placeholder JS/CSS/HTML content.
- Check JS `getElementById` / selector references exist in HTML.
- For calculator/form-like task families, check for at least:
  - a form or equivalent input container,
  - weight/height-style inputs when requested,
  - a submit/calculate button,
  - a result output element.

### Out of scope

- Browser automation.
- Executing JavaScript.
- Full HTML/CSS/JS parsing with a new framework dependency.
- A hardcoded BMI-only production verifier.

## Proposed Work

1. Extend `StaticTaskVerifier` through a small web-app task family check or a
   dedicated verifier strategy.
2. Reuse simple static parsing already present for selector/linkage checks.
3. Keep checks explainable and deterministic.
4. Add a transcript-shaped BMI repair scenario as an end-to-end guard.
5. Add smaller unit tests for each static rule.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Test / Verification Plan

- Unit tests:
  - missing JS link fails,
  - missing CSS link fails,
  - duplicate links fail,
  - placeholder JS fails,
  - JS references missing DOM IDs fails,
  - basic valid HTML/CSS/JS app passes.
- E2E scenario:
  - initial broken BMI files,
  - model writes partial app,
  - verifier refuses to claim task completion.

## Acceptance Criteria

- A web-app task cannot be marked task-verified if HTML does not link the JS.
- Placeholder `scripts.js` fails verification.
- Duplicate stylesheet/script references fail verification.
- HTML/CSS/JS linkage failures are reported in user-visible final answers.
- Generic non-web file writes are not forced through web-app verification.
