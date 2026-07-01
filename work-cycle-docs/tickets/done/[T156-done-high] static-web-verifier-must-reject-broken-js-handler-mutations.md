# T156 - Static Web Verifier Must Reject Broken JS Handler Mutations

Status: done
Priority: high

## Evidence Summary

- Source: focused T152 static web full-rewrite gate audit
- Date: 2026-05-06
- Model/backend: managed llama.cpp with `qwen2.5-coder:14b`
- Findings report:
  - `local/manual-testing/t152-static-web-full-rewrite-gate-audit-20260506-051126/FINDINGS-T152-STATIC-WEB-FULL-REWRITE-GATE-AUDIT.md`
- Transcript:
  - `local/manual-testing/t152-static-web-full-rewrite-gate-audit-20260506-051126/TEST-OUTPUT-T152-STATIC-WEB-FULL-REWRITE-GATE-QWEN-14B.txt`

Observed final `script.js`:

```javascript
document.querySelector('#run-button').addEventListener('click', () => {
  document.querySelector('#result').textC;
});
```

Talos reported:

```text
[Static verification: passed - Static web coherence checks passed for 1 mutated target(s).]
```

## Problem

The static web verifier accepted a broken JavaScript handler. The script references the right button and result selectors, but it does not set `#result` to `Clicked`; it contains a truncated `.textC;` expression.

This is not a T152 repair-control problem. T152 correctly enforces the full-rewrite gate. This is a verifier-strength problem: selector coherence alone is not enough for simple requested DOM behavior.

## Goal

Static web verification should reject obviously broken JavaScript handler mutations for the button/result fixture class.

## Scope

In scope:

- Detect malformed or incomplete JavaScript assignment patterns in small static web files when the user requested a button update.
- Require the repaired script to actually assign the expected result text when the prompt says the button should set `#result` to `Clicked`.
- Keep the check deterministic; do not add an LLM verifier.
- Preserve existing positive cases where `textContent`, `innerText`, or an equivalent direct DOM text assignment sets the expected value.

Out of scope:

- No browser automation.
- No broad JavaScript parser dependency unless code inspection proves it is already available or extremely low risk.
- No full semantic JavaScript analysis.
- No CSS/layout validation.

## Acceptance Criteria

- The Qwen-shaped broken handler above fails static verification.
- A valid handler using `document.querySelector('#result').textContent = 'Clicked';` passes.
- A valid handler using `document.getElementById('result').textContent = 'Clicked';` passes.
- Failure output is failure-dominant and names the missing/incomplete result assignment.
- The verifier still catches missing selectors and wrong filenames as before.
- No regression to static web repairs that already pass with valid JS.

## Tests

Required tests:

- Unit test in `StaticTaskVerifierTest` for `.textC;` false positive.
- Unit test for valid `querySelector('#result').textContent = 'Clicked'`.
- Unit test for valid `getElementById('result').textContent = 'Clicked'`.
- Integration/tool-loop test if the verifier result changes outcome formatting.

Suggested verification commands:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.StaticTaskVerifierTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest
.\gradlew.bat --no-daemon e2eTest
.\gradlew.bat --no-daemon check
```

## Closeout - 2026-05-06

Implemented a request-scoped static behavior check for the button/result fixture class:

- When the request says the button should set result text to `Clicked`, static web verification now requires JavaScript to reference `#run-button`.
- It also requires a direct `#result` text assignment to `Clicked` through `querySelector('#result')` or `getElementById('result')` using `textContent`/`innerText`.
- The original Qwen-shaped `.textC;` mutation now fails static verification with a concrete problem naming `script.js`, `#result`, and `Clicked`.

Tests added:

- `staticButtonFixtureFailsWhenResultHandlerHasTruncatedTextContentAssignment`
- `staticButtonFixturePassesWhenQuerySelectorAssignsResultTextContent`
- `staticButtonFixturePassesWhenGetElementByIdAssignsResultTextContent`

Verification run:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.staticButtonFixtureFailsWhenResultHandlerHasTruncatedTextContentAssignment
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.staticButtonFixtureFailsWhenResultHandlerHasTruncatedTextContentAssignment --tests dev.talos.runtime.verification.StaticTaskVerifierTest.staticButtonFixturePassesWhenQuerySelectorAssignsResultTextContent --tests dev.talos.runtime.verification.StaticTaskVerifierTest.staticButtonFixturePassesWhenGetElementByIdAssignsResultTextContent --tests dev.talos.runtime.verification.StaticTaskVerifierTest.staticWebRepairContextFilesDoNotAllNeedMutationWhenFinalSurfacePasses
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.StaticTaskVerifierTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon e2eTest
.\gradlew.bat --no-daemon check
.\gradlew.bat --no-daemon installDist
```

Focused audit:

- `local/manual-testing/t156-static-web-verifier-audit-20260506-063043/FINDINGS-T156-STATIC-WEB-VERIFIER-AUDIT.md`
