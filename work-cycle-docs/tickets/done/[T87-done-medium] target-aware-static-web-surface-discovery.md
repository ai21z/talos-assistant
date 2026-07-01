# [T87-done-medium] Target-Aware Static Web Surface Discovery

Status: done
Priority: medium
Date: 2026-05-02
Closed: 2026-05-02

## Source

Follow-up from the T83-T86 focused installed audit:

- Summary:
  `local/manual-testing/t83-t86-focused-audit-20260502-142518/SUMMARY-T83-T86-FOCUSED.md`
- Combined transcript:
  `local/manual-testing/t83-t86-focused-audit-20260502-142518/TEST-OUTPUT-T83-T86-FOCUSED.txt`
- Dedicated T84 transcript:
  `local/manual-testing/t84-web-focused-audit-20260502-142518/TEST-OUTPUT-T84-WEB-FOCUSED.txt`

The dedicated `script.js` selector repair passed static web coherence in a
small web-only workspace. The same repair passed on disk but failed static
verification in the combined audit workspace because unrelated visible files
pushed the root file count above the generic small-workspace limit.

## Problem

`StaticTaskVerifier.obviousPrimaryFiles(...)` is intentionally conservative for
generic discovery, but post-mutation verification has stronger evidence: it
knows the mutated target path. For a web-file mutation such as `script.js`, the
verifier should be able to discover bounded sibling web files in the same
workspace even when a few unrelated root files are present.

The current behavior is too sensitive to the total visible file count. It can
report:

`web coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.`

even when the actual HTML/CSS/JS surface is small, linked, and directly related
to the mutated target.

## Goal

For post-mutation static web verification:

- if a successful mutation target is a web file;
- and the root exposes a bounded, unambiguous HTML/CSS/JS sibling surface;
- then use that target-aware sibling surface for selector/linkage verification,
  even if the root also contains a few unrelated non-web files.

## Non-Goals

- Do not broaden generic prompt-side `obviousPrimaryFiles(...)` discovery.
- Do not scan recursively through large projects.
- Do not treat ambiguous multi-app workspaces as safe.
- Do not add browser execution or dynamic JavaScript evaluation.

## Acceptance Criteria

- A `script.js` selector repair passes static web coherence when the workspace
  also contains unrelated visible files such as `README.md`, `config.json`,
  `notes.md`, and `report.docx`.
- The verifier still refuses ambiguous web surfaces with too many candidate web
  files.
- Existing T84 behavior for a small web workspace remains green.
- Existing T85/T86 read/list behavior remains unchanged.
- Add a unit regression matching the combined audit fixture shape.
- Run an installed focused audit for the combined fixture shape before closing.

## Changes

- Added a verifier-only target-aware fallback for static web surface discovery.
- Kept generic `obviousPrimaryFiles(...)` discovery at its existing conservative
  root-visible-file limit.
- Allowed post-mutation verification to use a successful root-level web
  mutation target to discover bounded sibling web files in mixed small
  workspaces.
- Kept ambiguity containment: target-aware discovery refuses surfaces with more
  than the existing primary web-file cap.

## TDD Evidence

Red:

- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesTargetAwareWebSurfaceDespiteMixedWorkspaceFiles --no-daemon`
- Failed with the audited message:
  `web coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.`

Green:

- The same targeted test passed after adding target-aware discovery.
- Added a conservative guard:
  `targetAwareWebSurfaceRefusesTooManyCandidateWebFiles`.

## Verification

- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixUsesTargetAwareWebSurfaceDespiteMixedWorkspaceFiles --tests dev.talos.runtime.verification.StaticTaskVerifierTest.targetAwareWebSurfaceRefusesTooManyCandidateWebFiles --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest full TalosBench summary:

- `local/manual-testing/talosbench/20260502-152817/summary.md`

Focused installed audit:

- Workspace:
  `local/manual-workspaces/t87-focused-audit-20260502-152749`
- Transcript:
  `local/manual-testing/t87-focused-audit-20260502-152749/TEST-OUTPUT-T87-FOCUSED.txt`
- Result:
  `talos.edit_file -> script.js [ok]`
- Static verification:
  `passed - Static web coherence checks passed for 1 mutated target(s).`
- Trace:
  `trc-3ce4d6ad-5f87-4e5c-bcfa-e36944600130`

Note: an earlier full TalosBench run at
`local/manual-testing/talosbench/20260502-152509/summary.md` produced a
non-reproducible `t57-read-config-requires-evidence` model-output failure where
the model returned `{"name":"None","arguments":{}}` after a successful read. The
case passed on immediate single-case rerun and the subsequent full TalosBench
pack passed. No T87 code path participates in read-only evidence answering.
