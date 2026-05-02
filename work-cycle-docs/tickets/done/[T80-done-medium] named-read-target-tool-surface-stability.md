# [T80-done-medium] Named Read Target Tool Surface Stability

Status: done
Priority: medium
Date: 2026-05-02
Closed: 2026-05-02

## Evidence Summary

- Installed TalosBench run:
  `local/manual-testing/talosbench/20260502-113613/summary.md`
- Failing case:
  `t57-read-config-requires-evidence`

Observed behavior:

- Talos correctly derived `READ_TARGET_REQUIRED`.
- Talos successfully called `talos.read_file` on `config.json`.
- The model then wandered into extra read-only tools and contradicted the
  observed file content.

## Goal

For read-only turns with explicit expected file targets, expose only
`talos.read_file` to the model. This keeps the tool surface aligned with the
evidence obligation and reduces unnecessary post-read tool drift.

## Non-Goals

- Do not change directory-listing tool policy.
- Do not change mutating apply-phase tool policy.
- Do not disable read-only workspace inspection for prompts without explicit
  file targets.

## Closure Notes

- Narrowed native tool selection for non-mutating expected-target turns to
  `talos.read_file`.
- Updated the unsupported-docx TalosBench case to assert mutating tools are
  absent from `nativeTools`, instead of banning safety guidance text from the
  whole trace transcript.

## Verification

- `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.NativeToolSpecPolicyTest" --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`
