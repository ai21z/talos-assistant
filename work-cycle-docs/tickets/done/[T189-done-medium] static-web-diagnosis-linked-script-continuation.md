# T189 - Static Web Diagnosis Linked-Script Continuation

Status: done
Severity: medium

## Problem

T61N found a safe but weak Qwen path for read-only static web diagnosis.

The user asked:

`Review the current static web page and say whether the button can work in a browser. Do not inspect protected files.`

Qwen read only `index.html`. Talos correctly detected that the linked script source `script.js` had not been read and returned an evidence-incomplete containment answer.

This was safe, but the runtime already knew the missing linked script target. Product behavior stopped at containment instead of deterministically gathering the remaining read-only evidence.

## Implementation

- Added linked local script target discovery to `EvidenceObligationVerifier`.
- Added a bounded inspect continuation path in `AssistantTurnExecutor` that combines existing primary-read gaps with missing linked script reads.
- Preserved protected-path filtering for continuation targets.
- Updated runtime-owned static web diagnostics rendering to use the actual read-path hints from the turn.
- Kept evidence-incomplete containment when the continuation is ignored or produces no read tool call.

## Verification

Targeted tests:

- `AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests.staticButtonReviewReadsLinkedScriptWhenFullFixtureSkipsPrimaryRetry`
- `AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests.linkedScriptInspectContinuationIgnoresProtectedAndExternalScripts`
- `AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests.linkedScriptContinuationNoToolRetryKeepsEvidenceIncompleteContainment`
- `EvidenceObligationVerifierTest.missingLinkedScriptReadTargetsNamesExistingUnreadLocalScripts`
- `EvidenceObligationVerifierTest.missingLinkedScriptReadTargetsEmptyAfterLinkedScriptRead`
- `StaticTaskVerifierTest.readOnlyWebDiagnosticsUseReadPathHintsInFullAuditFixture`

Broader tests:

- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat build --no-daemon`
- `.\gradlew.bat installDist --no-daemon`

Focused audit:

- `local/manual-testing/t189-focused-linked-script-audit-20260507-161704/FINDINGS-T189-FOCUSED-LINKED-SCRIPT.md`

Audit result:

- Qwen exercised the old weak path, Talos requested `script.js`, Qwen read it, and the visible final answer was runtime-owned static diagnostics.
- GPT-OSS read `script.js` during the normal tool loop and kept the happy path intact.
- No protected fixture content was exposed.

## Follow-Up

Qwen's visible output showed two separate read-only tool-summary banners because the continuation loop contributed its own summary. This is a small UX polish issue, not a T189 correctness blocker.
