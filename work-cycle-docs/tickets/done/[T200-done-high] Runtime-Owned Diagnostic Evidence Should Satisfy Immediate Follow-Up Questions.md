# T200 - Runtime-Owned Diagnostic Evidence Should Satisfy Immediate Follow-Up Questions

Status: done
Severity: high

## Problem

The T199 focused re-audit confirmed static-web under-inspection containment, but exposed a follow-up evidence gap.

When Qwen was asked:

`Based only on verified file evidence from the previous answer, list the blockers that prevent the button from working. Do not inspect protected files.`

Talos classified the turn as a fresh static-web diagnosis, required fresh current-turn static-web evidence, and returned:

`[Evidence incomplete: required workspace evidence was not gathered in this turn.]`

This was safe, but too strict. The previous answer was runtime-owned static-web diagnostics, not arbitrary model prose. Talos should be able to answer immediate follow-up questions from its own runtime-owned diagnostic evidence.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t199-focused-re-audit-20260507-190602/FINDINGS-LLAMA-CPP-T199-FOCUSED-RE-AUDIT.md`

Transcript evidence:

- Qwen follow-up prompt and fresh static-web evidence obligation: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:530-548`
- Evidence-incomplete final answer: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:551-558`
- Trace confirms only `talos.list_dir` ran: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:564-577`
- GPT-OSS answered correctly only because it chose to read `index.html` and `script.js` again: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:459-472`

## Implementation

- Added a deterministic immediate follow-up path in `AssistantTurnExecutor`.
- The path only fires when the current request explicitly refers to previous/verified evidence and asks for blockers, findings, issues, or diagnosis.
- The previous assistant answer must have Talos's runtime-owned static diagnostic shape:
  - `I inspected the primary web files:`
  - `Static web diagnostics found:` or `Static web diagnostics did not find obvious...`
  - `No files were changed.`
- The response extracts blocker lines from that diagnostic block.
- Arbitrary prior model prose is not trusted as evidence.

## Verification

- Red test observed:
  `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$VerifiedFollowUpSummaries.staticWebDiagnosticFollowUpUsesPreviousRuntimeOwnedDiagnostics' --no-daemon`
- Focused verified-follow-up suite passed:
  `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$VerifiedFollowUpSummaries' --no-daemon`
- Static-web diagnostics suite passed:
  `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$ReadOnlyWebDiagnosticsGroundingTests' --no-daemon`
- `AssistantTurnExecutorTest` passed after rerunning sequentially; the first parallel attempt collided on Gradle's Windows test-result cleanup file, not on a test assertion.

Full test/build verification is recorded in the implementation turn before commit.
