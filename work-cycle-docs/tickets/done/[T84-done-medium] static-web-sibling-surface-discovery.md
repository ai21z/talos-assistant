# [T84-done-medium] Static Web Sibling Surface Discovery

Status: done
Priority: medium
Date: 2026-05-02
Closed: 2026-05-02

## Source

Follow-up from the T82 focused audit:

- Summary: `local/manual-testing/t82-focused-audit-20260502-124432/SUMMARY-T82-FOCUSED.md`
- Transcript: `local/manual-testing/t82-focused-audit-20260502-124432/TEST-OUTPUT-T82-FOCUSED.txt`

Finding F2 showed that this repair succeeded on disk but failed static
verification:

`Make script.js fix the selector bug by changing .missing-button to .cta-button.`

The workspace contained `index.html`, `styles.css`, and `script.js`, but the
verifier reported that it could not discover a small HTML/CSS/JS surface.

## Problem

`StaticTaskVerifier.obviousPrimaryFiles(...)` treated any incidental non-web
file in a small workspace as proof that no primary web surface existed. A
script-only or style-only repair could therefore fail web coherence
verification even when sibling HTML/CSS/JS files were present and bounded.

## Goal

For small web workspaces, static verification should discover sibling
HTML/CSS/JS files for script-only and style-only repairs while keeping the
discovery bounded and conservative.

## Non-Goals

- Do not scan large workspaces broadly.
- Do not treat hidden files as primary web surface.
- Do not introduce browser execution or dynamic JavaScript evaluation.

## Changes

- Updated primary web file discovery to:
  - ignore hidden files;
  - tolerate incidental non-web files in small workspaces;
  - keep strict bounds on total visible files and primary web files;
  - return sorted HTML/CSS/JS sibling files.
- Added a regression where `README.md` is present alongside
  `index.html`, `styles.css`, and `script.js`, and a script-only selector fix
  passes static web coherence.

## TDD Evidence

Red:

- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesSiblingWebSurfaceDespiteReadme --no-daemon`
- Failed with the audited "workspace does not expose a small HTML/CSS/JS
  surface" result.

Green:

- The same targeted test passed after bounded sibling-surface discovery.

## Verification

- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesSiblingWebSurfaceDespiteReadme --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest --tests dev.talos.cli.modes.ExecutionOutcomeTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest full TalosBench summary:

- `local/manual-testing/talosbench/20260502-134135/summary.md`

Focused installed audit:

- Transcript:
  `local/manual-testing/t83-t84-focused-audit-20260502-131145/TEST-OUTPUT-T83-T84-FOCUSED.txt`
- `talos.edit_file -> script.js [ok]`
- Static verification passed with web coherence checks.
- Final `script.js` contained `.cta-button`.
