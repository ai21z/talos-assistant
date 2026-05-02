# [T79-done-low] Prompt Audit Frame Preview Visibility

Status: done
Priority: low
Date: 2026-05-02
Closed: 2026-05-02

## Evidence Summary

- Installed TalosBench run:
  `local/manual-testing/talosbench/20260502-113033/summary.md`
- Failed cases:
  - `t68-no-inspection-methodology-direct-answer`
  - `t68-list-only-negative-content`

Observed behavior:

- Both installed cases used the correct contract and tool surface.
- No hidden fixture content leaked.
- The assertions failed because `framePreview` truncated before the relevant
  current-turn policy directives.

## Goal

Make prompt-audit current-turn frame previews long enough for TalosBench and
manual `/last trace` review to confirm the decisive policy directives.

## Non-Goals

- Do not change tool-surface selection.
- Do not alter task classification.
- Do not store raw full prompts in trace output.

## Closure Notes

- Increased the redacted prompt-audit preview cap from 240 to 800 characters.
- Added unit coverage that direct-answer and directory-listing policy directives
  remain visible in the redacted current-turn frame preview.

## Verification

- `.\gradlew.bat test --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --no-daemon`
