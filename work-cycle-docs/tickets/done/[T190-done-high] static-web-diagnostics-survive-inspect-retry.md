# T190 - Static Web Diagnostics Survive Inspect Retry

Status: done
Severity: high

## Problem

T188 added runtime-owned static button diagnostics, but the focused audit found that the deterministic diagnostic could be lost after an inspect-completeness retry.

The first loop could read `index.html` and `script.js` and correctly produce:

`Static web diagnostics found: script.js: button click handler references #result but does not assign visible result text with textContent or innerText.`

If `styles.css` was still missing from the obvious primary file set, the inspect-completeness retry asked the model to read it. The retry loop then carried only the retry loop's read paths. That meant final read-only web diagnostic shaping no longer saw the earlier HTML+script evidence and model-authored bad prose could win.

## Evidence

Failing focused audit:

`local/manual-testing/t188-focused-static-button-audit-20260507-153637/`

Key observations:

- GPT-OSS answered acceptably after reading `index.html`, `script.js`, and `styles.css`.
- Qwen correctly identified the broken `result.textC;` line but then gave an invalid "Possible Fix" that repeated the same bad code and claimed the button should work.
- Prompt debug showed Talos generated the runtime-owned diagnostic before the CSS retry.
- The CSS retry loop only carried `styles.css` in `readPaths`, so final runtime-owned diagnostic override did not apply.

## Scope

In scope:

- Preserve read evidence across read-only inspect-completeness retries.
- Keep mutation retry behavior unchanged.
- Ensure final read-only web diagnostic shaping can use the combined original+retry read surface.
- Add a regression test for the exact audit shape.

Out of scope:

- Prompt wording changes.
- Browser execution.
- T189 linked-script continuation.
- General multi-turn evidence memory.

## Implementation

- Added a read-only inspect retry evidence merge in `AssistantTurnExecutor`.
- The retry loop result keeps its final answer and failure state, but its read-path evidence is merged with the original loop when both loops are read-only.
- This lets final runtime-owned static web diagnostics see the combined `index.html` + `script.js` + `styles.css` evidence after a retry.
- Added a regression test for the exact audit shape: original loop reads HTML+JS, retry reads CSS, retry model returns bad prose, final answer remains deterministic diagnostics.

## Verification

- Red test:
  - `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests.staticButtonDiagnosticsSurviveInspectCompletenessRetry' --no-daemon`
- Green verification:
  - `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests.staticButtonDiagnosticsSurviveInspectCompletenessRetry' --no-daemon`
  - `.\gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.runtime.verification.StaticTaskVerifierTest --no-daemon`
  - `.\gradlew.bat test --no-daemon`
  - `.\gradlew.bat e2eTest --tests 'dev.talos.harness.JsonScenarioPackTest.readOnlyWebDiagnosticsShortCircuit' --no-daemon`
  - `.\gradlew.bat build --no-daemon`
  - `.\gradlew.bat installDist --no-daemon`

## Audit

Passing focused audit:

`local/manual-testing/t190-focused-static-button-retry-audit-20260507-155901/FINDINGS-T190-FOCUSED-STATIC-BUTTON-RETRY.md`

Both Qwen and GPT-OSS produced the runtime-owned diagnostic as the visible final answer. No protected file contents were read.
