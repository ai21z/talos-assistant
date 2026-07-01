# T199 - Static-Web Diagnostic Underinspection Must Not Emit Speculative Success

Status: done
Severity: high

## Problem

The T197/T198 focused re-audit confirmed that runtime-owned static diagnostics work when the model reads the full static web surface. GPT-OSS read `index.html`, `styles.css`, and `script.js`, and Talos produced grounded diagnostics.

Qwen exposed a narrower failure: for the same read-only static-web diagnostic prompt, it read only `index.html`. Talos then allowed model-authored speculative fix/success prose through, including broken JavaScript copied from the fixture shape and a claim that the button "should work".

This was not a static verifier bug. It was an under-inspection handoff bug: `AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded` only took over when both HTML and JavaScript had been read.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t197-t198-focused-re-audit-20260507-184608/FINDINGS-LLAMA-CPP-T197-T198-FOCUSED-RE-AUDIT.md`

Important transcript evidence:

- Qwen read only `index.html`: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:44-45`
- Qwen emitted speculative fix/success prose: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:51-84`
- Trace confirms the turn was accepted as read-only answered: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:90-103`

## Implementation

- Broadened `AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded` so deterministic diagnostics may take over after an anchor static-web read, not only after both HTML and JavaScript were read.
- Preserved linked-script evidence containment: if an HTML read reveals an existing linked script that was not read, Talos still leaves the evidence obligation path to report incomplete evidence.
- Protected static import questions from being overwritten by generic static-web diagnostics.
- Added a Qwen-shaped integration test where the model reads only `index.html` and then emits speculative code/success prose.

## Verification

- Red test observed:
  `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests.staticButtonReviewGroundsHtmlOnlyUnderinspectionWhenVisibleScriptIsUnlinked' --no-daemon`
- Focused test passed after implementation.
- Surrounding read-only web diagnostics suite passed:
  `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests' --no-daemon`
- `AssistantTurnExecutorTest` passed.
- `StaticTaskVerifierTest` passed after rerunning sequentially; the first parallel attempt collided on Gradle's Windows test-result cleanup file, not on a test assertion.

Full test/build verification is recorded in the implementation turn before commit.
