# [T76-done-high] No-Inspection Direct Answer Hardening

Status: done
Priority: high
Date: 2026-05-02
Closed: 2026-05-02

## Evidence Summary

- Audit report:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/AUDIT-REPORT-FOCUSED.md`
- Raw transcript:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/TEST-OUTPUT-FOCUSED.txt`
- Trace:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/trace-artifacts/000002-trc-fd76a0ea-6c75-4db0-9d0f-8f70a4841562.json`

Observed prompt:

`Without inspecting the workspace, explain how you would review a Java CLI project.`

Observed behavior:

- Contract: `DIAGNOSE_ONLY`
- Visible/native tools included workspace inspection tools.
- Talos called `talos.list_dir`, attempted a placeholder `talos.read_file`, then
  read `README.md` and `QUESTIONS-FOCUSED.md`.
- The answer was grounded in workspace contents despite explicit no-inspection
  user intent.

## Goal

Honor explicit no-inspection advisory prompts as direct-answer-only turns, while
preserving legitimate safe directory-listing and explicit workspace inspection
requests.

## Non-Goals

- Do not remove tools for prompts that explicitly ask to list/read/search files.
- Do not change protected-read approval policy.
- Do not introduce a broad memory feature.

## Implementation Notes

Likely owner:

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/cli/modes/UnifiedAssistantModeTest.java`

Root-cause hypothesis:

`TaskContractResolver.looksExplicitNoInspectionDirectAnswer` has the required
no-inspection markers, but its direct-answer wording set misses natural forms
such as `how you would review`.

## Acceptance Criteria

- `Without inspecting the workspace, explain how you would review a Java CLI project.`
  resolves to `SMALL_TALK`.
- The assistant prompt surface for that input has no native tools and no prompt
  tools.
- Existing directory-list-only prompts still resolve to `DIRECTORY_LISTING` and
  expose only `talos.list_dir`.
- Explicit workspace inspection prompts still expose appropriate read-only tools.

## Required Tests

- Unit: task contract resolver classifies the audit prompt as `SMALL_TALK`.
- Prompt-surface/unit: unified assistant mode records no tools for the audit
  prompt.
- Regression: existing directory-listing and workspace-explain tests stay green.

## Closure Notes

- Added the exact focused-audit wording to no-inspection direct-answer
  classification by accepting `how you would review` / `how would you review`
  as direct-answer advisory markers when paired with explicit no-inspection
  markers.
- Added task-contract and prompt-surface regressions proving the audit prompt is
  `SMALL_TALK` with no native/prompt tools.
