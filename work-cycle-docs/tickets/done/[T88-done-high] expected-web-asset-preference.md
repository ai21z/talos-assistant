# T88 - Expected Web Asset Preference During Static Verification

Status: Done
Priority: High
Branch: v0.9.0-beta-dev

## Source

- T61-C milestone QA audit: `local/manual-workspaces/t61-c-milestone-qa-20260502-155141/TEST-OUTPUT-T61-C.txt`
- T61-C findings: `local/manual-workspaces/t61-c-milestone-qa-20260502-155141/FINDINGS-T61-C.md`
- Full run trace: `trc-e77a9e01-fe15-49a1-8718-d03855f11013`
- Focused create trace: `trc-6e12d9c9-7a22-4212-ad37-7c92454a32e3`
- Focused repair trace: `trc-c700dfee-4c57-473d-8c74-8fa2541fea16`

## Problem

When a small static web workspace contains the current requested JavaScript target `scripts.js` and a stale sibling `script.js`, static verification could choose the stale file if HTML omitted a script link. That produced misleading repair evidence such as `HTML does not link JavaScript file: script.js` and stale selector errors from unrelated legacy code.

## Implementation

- `StaticTaskVerifier` now passes expected and successfully mutated web target hints into selector fact selection.
- Asset selection order is now:
  1. HTML-linked asset, preserving explicit workspace evidence.
  2. Expected or successfully mutated root-level web target for the same extension.
  3. Existing primary-file fallback for ambiguous cases with no current target evidence.
- The change is scoped to small static web verification. Render-only selector diagnostics keep the existing no-preference path.
- The T87 target-aware surface discovery behavior is preserved.

## Acceptance Evidence

- Added regression test `expectedJavaScriptTargetBeatsStaleSiblingWhenHtmlLinkIsMissing`.
- Verified the test failed before the implementation with stale diagnostics:
  - `HTML does not link JavaScript file: script.js`
  - `JavaScript references missing class selectors: .missing-button`
- Verified the test passes after implementation and diagnostics now prefer `scripts.js`.
- Verified neighboring T87/linkage guards still pass.

## Verification

- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.expectedJavaScriptTargetBeatsStaleSiblingWhenHtmlLinkIsMissing --no-daemon` - failed red before implementation, passed after implementation.
- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesTargetAwareWebSurfaceDespiteMixedWorkspaceFiles --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesSiblingWebSurfaceDespiteReadme --tests dev.talos.runtime.verification.StaticTaskVerifierTest.targetAwareWebSurfaceRefusesTooManyCandidateWebFiles --tests dev.talos.runtime.verification.StaticTaskVerifierTest.linkedCssFileIsPreferredOverLegacyCssNeighbor --no-daemon` - passed.
- `.\gradlew.bat test --no-daemon` - passed.
- `.\gradlew.bat installDist --no-daemon` - passed.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat` - completed. Summary: `local/manual-testing/talosbench/20260502-174011/summary.md`; automated cases passed and approval-sensitive cases remained `MANUAL_REQUIRED`.

## Residual Risk

- Ambiguous multi-asset workspaces without linked, expected, or mutated target evidence still use the existing conservative primary-file path. This is intentional for T88 and keeps the fix local to current-turn target evidence.
