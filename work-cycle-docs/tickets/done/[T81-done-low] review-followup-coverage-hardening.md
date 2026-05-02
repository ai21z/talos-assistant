# [T81-done-low] Review Follow-up Coverage Hardening

Status: done
Priority: low
Date: 2026-05-02
Closed: 2026-05-02

## Source

Follow-up from the external review of T76-T80 on branch
`v0.9.0-beta-dev` at HEAD `8bf7de6`.

## Goal

Close narrow coverage gaps without changing runtime behavior:

- Exercise the exact T61-B/T76 no-inspection wording in TalosBench:
  `Without inspecting the workspace, explain how you would review a Java CLI project.`
- Add prompt-audit regression coverage proving secret-like assignments remain
  redacted when they appear after the old 240-character frame preview boundary.
- Make T80's intended scope explicit in unit tests: non-mutating contracts with
  expected file targets expose only `talos.read_file`, while read-only prompts
  without expected targets keep the broader read-only inspection surface.

## Non-Goals

- Do not change task classification.
- Do not narrow T80 to `READ_ONLY_QA` only.
- Do not change prompt-audit redaction behavior beyond tests.

## Changes

- Updated `t68-no-inspection-methodology-direct-answer` to use the exact audit
  wording that exposed the original no-inspection methodology bug.
- Added `NativeToolSpecPolicyTest` coverage for `WORKSPACE_EXPLAIN` and
  `VERIFY_ONLY` expected-target contracts.
- Added a `PromptAuditSnapshotTest` case for redaction after the former frame
  preview cap.

## Verification

- `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.NativeToolSpecPolicyTest" --no-daemon`
- `.\gradlew.bat test --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId t68-no-inspection-methodology-direct-answer,t57-read-config-requires-evidence`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest full TalosBench summary:

- `local/manual-testing/talosbench/20260502-123226/summary.md`
