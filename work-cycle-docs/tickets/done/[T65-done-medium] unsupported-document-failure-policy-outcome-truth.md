# [T65-done-medium] Unsupported Document Failure Policy Outcome Truth

Status: done
Priority: medium
Date: 2026-05-01

## Evidence Summary

- Source: T61 manual audit
- Transcript: `local/manual-workspaces/t61-audit-20260501-110306/TEST-OUTPUT-T61.txt`
- Related completed ticket:
  `work-cycle-docs/tickets/done/talos-unsupported-binary-document-honesty.md`

Observed behavior:

- Prompt: `Can you read report.docx and summarize it?`
- Talos correctly detects `report.docx` as unsupported:
  `UNSUPPORTED_FORMAT: Unsupported binary document format`.
- After the unsupported read, Talos tries speculative fallback filenames:
  `report.txt` and `extracted_report.txt`.
- Failure policy stops the loop after three failed `read_file` calls.
- The user-facing answer is honest about unsupported document capability.
- `/last trace` still records Local Trace `Outcome: COMPLETE
  (READ_ONLY_ANSWERED)`.

Important line references:

- Unsupported read and speculative fallback reads:
  `TEST-OUTPUT-T61.txt:844-884`
- Trace tools and blocked details:
  `TEST-OUTPUT-T61.txt:887-948`

## Classification

Primary taxonomy bucket: `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `OUTCOME_TRUTH`
- `FAILURE_POLICY`
- `EVIDENCE_OBLIGATION`

Blocker level: medium follow-up

Why this level:

The final answer is now mostly honest, so this is not the original severe
unsupported-document bug. The remaining issue is trace/outcome truth and noisy
tool-loop behavior after the unsupported target is already known.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Let the model keep guessing converted filenames until failure policy stops it.
```

Architectural hypothesis:

```text
Unsupported target evidence should be terminal for that requested target unless
the user explicitly provides an alternate converted file. Failure policy stops
and unsupported-format blocks must dominate the final trace outcome instead of
rendering as COMPLETE.
```

Likely code/document areas:

- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/repair/` or tool-loop failure policy area
- `src/e2eTest/resources/scenarios/`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Stop unsupported document reads cleanly and make trace outcome truth match the
capability limitation.

## Non-Goals

- No PDF/DOCX extraction.
- No Apache Tika/PDFBox/POI dependency.
- No browser or external conversion path.
- No generic retry suppression for all failed reads.

## Acceptance Criteria

- After `report.docx` returns `UNSUPPORTED_FORMAT`, Talos does not guess
  `report.txt`, `extracted_report.txt`, or similar derived filenames unless
  the user explicitly asks for them.
- Failure policy stop after unsupported document reads does not render Local
  Trace outcome as `COMPLETE (READ_ONLY_ANSWERED)`.
- `/last trace` records an unsupported/advisory/blocked outcome that is
  consistent with the final answer.
- The final answer remains capability-honest and does not claim document
  content was inspected.
- Existing unsupported binary document honesty tests continue to pass.
- TalosBench `t57-unsupported-docx` asserts no speculative fallback reads if
  the runner can do so without brittle prose matching.

## Tests / Evidence

Required deterministic regression:

- E2E scenario: unsupported `report.docx` read performs at most the target read
  and optional directory listing, not speculative fallback reads.
- Outcome test: unsupported target/failure-policy stop cannot produce
  `COMPLETE (READ_ONLY_ANSWERED)`.
- Trace assertion test: unsupported capability appears in `Blocked` or the
  equivalent failure-truth field.

Suggested commands:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat e2eTest --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId t57-unsupported-docx
```

## Completion Notes

Completed on 2026-05-01.

- Unsupported binary document read evidence now dominates outcome truth as
  `ADVISORY_ONLY` instead of `COMPLETE (READ_ONLY_ANSWERED)`.
- The tool loop stops after an unsupported document read when that iteration
  gathered no successful evidence, preventing speculative fallback reads such
  as `report.txt` and `extracted_report.txt`.
- User-provided converted targets remain allowed: if the user explicitly names
  `report.txt` or `extracted_report.txt`, Talos may read that target after the
  unsupported `report.docx` failure.
- Mixed evidence remains supported: if a turn reads supported text evidence and
  also encounters unsupported documents, the loop can still synthesize from the
  gathered supported evidence.
- Added deterministic unit coverage for unsupported-format outcome and local
  trace classification.
- Added e2e coverage for a `report.docx` prompt where scripted fallback reads
  must not execute, plus the explicit converted-target exception.
- Strengthened TalosBench `t57-unsupported-docx` to reject speculative fallback
  filenames and require an advisory local trace outcome.
- Verification passed:
  `.\gradlew.bat test e2eTest --no-daemon`,
  `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest`,
  `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`, and installed
  TalosBench case `t57-unsupported-docx`.
- Main-workspace TalosBench summary:
  `local/manual-testing/talosbench/20260501-125431/summary.md`.

## Known Risks

- Some helpful fallback behavior may be legitimate when the user names both a
  binary document and a converted text file. Keep the stop condition tied to
  model-invented fallback names, not user-provided targets.
