# [T82-done-medium] Mixed Protected/Public Read Handoff

Status: done
Priority: medium
Date: 2026-05-02
Closed: 2026-05-02

## Source

Follow-up from the external review of T76-T80, finding F6:
`readEvidenceHandoffTargets` filtered targets by evidence-obligation bucket and
could silently omit public targets when any protected target made the turn a
`PROTECTED_READ_APPROVAL_REQUIRED` turn.

## Problem

For a prompt such as:

`Read .env and README.md and tell me what both say.`

Talos derived `PROTECTED_READ_APPROVAL_REQUIRED` because `.env` is protected.
The runtime handoff then selected only the protected target. The evidence
verifier still required every expected target, so the public target could remain
unread and the turn could be marked incomplete even after approval.

## Goal

When the user explicitly asks to read a protected target and a public target in
the same turn:

- ask approval only for the protected target;
- read every explicit expected target through the runtime handoff;
- preserve the protected-read intent gate so stale or negated protected mentions
  do not trigger approval or protected content access.

## Non-Goals

- Do not relax `ProtectedPathPolicy`.
- Do not bypass approval for protected reads.
- Do not re-enable streaming for read-evidence turns.
- Do not change evidence verification semantics.

## Changes

- Added a regression test proving mixed protected/public read recovery gathers
  both `.env` and `README.md` after approval.
- Changed protected-read handoff target selection to gather all explicit
  expected targets after verifying current protected-read intent against only
  the protected subset.

## TDD Evidence

Red:

- `.\gradlew.bat test --tests "*mixedProtectedAndPublicReadNoToolHandoffReadsAllExpectedTargetsAfterApproval" --no-daemon`
- Failed with one `talos.read_file` handoff and a protected-read incomplete
  message listing required targets `README.md, .env`.

Green:

- Same targeted test passed after the handoff target fix.

## Verification

- `.\gradlew.bat test --tests "*mixedProtectedAndPublicReadNoToolHandoffReadsAllExpectedTargetsAfterApproval" --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId t68-no-inspection-methodology-direct-answer,t57-read-config-requires-evidence`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest full TalosBench summary:

- `local/manual-testing/talosbench/20260502-123226/summary.md`
