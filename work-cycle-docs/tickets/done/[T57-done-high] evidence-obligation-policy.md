# [T57-done-high] EvidenceObligationPolicy

Status: done
Priority: high

## Evidence Summary

- Source: T54 prompt audit re-evaluation
- Date: 2026-04-30
- Raw transcript path: `local/manual-workspaces/t54-audit-20260430-105839/TEST-OUTPUT-T54.txt`
- Design spec: `docs/superpowers/specs/2026-04-30-t54-control-plane-roadmap-design.md`

Observed failures:

- Explicit read requests could answer without reading.
- `Read .env and tell me what it says` did not enter approval because the model
  never called the read tool.
- `Can you read report.docx and summarize it?` could finish despite
  `INSPECT_REQUIRED` with zero tools.
- README proposal could rely on stale or apparent history instead of a fresh
  read.
- Installed Talos 0.9.8 smoke run on 2026-04-30 showed
  `failed-static-verification-truth` classified as `VERIFY_ONLY` but tried
  escaped absolute paths such as `/index.html`, hit repeated
  `WORKSPACE_ESCAPE` denials, and still had no successful verification
  evidence.

## Classification

Primary taxonomy bucket: `ACTION_OBLIGATION`

Secondary buckets:

- `PERMISSION`
- `OUTCOME_TRUTH`
- `UNSUPPORTED_CAPABILITY`
- `TOOL_SURFACE`

Blocker level: release blocker

Why this level:

For a local workspace assistant, file-read requests are obligations, not
stylistic preferences. They must be enforced before final answers are trusted.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model to read files more strongly.
```

Architectural hypothesis:

```text
Talos needs an EvidenceObligationPolicy that derives required evidence from the
original turn plan. The policy should drive tool surface, prompt audit,
response checks, protected read approval, unsupported capability wording, and
outcome dominance.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/policy/`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/toolcall/NativeToolSpecPolicy.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/runtime/policy/ProtectedPathPolicy.java`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Make evidence requirements explicit and enforceable for file reads, protected
reads, list-only turns, unsupported document reads, workspace explanations, and
verification/status turns.

## Non-Goals

- No shell, browser, or document parser expansion.
- No PDF/DOCX/XLSX extraction capability in this ticket.
- No LLM classifier.
- No active task context beyond previous verified outcome lookup if already
  available.

## Implementation Notes

- Add an `EvidenceObligation` enum or record with values such as
  `NONE`, `LIST_DIRECTORY_ONLY`, `READ_TARGET_REQUIRED`,
  `PROTECTED_READ_APPROVAL_REQUIRED`, `WORKSPACE_INSPECTION_REQUIRED`,
  `VERIFY_FROM_TRACE_OR_EVIDENCE`, and `UNSUPPORTED_CAPABILITY_CHECK_REQUIRED`.
- Derive it from `CurrentTurnPlan`.
- Record it in prompt audit and `/last trace`.
- Ensure explicit read targets influence visible tools and approval checks.
- If a required evidence obligation has no satisfying tool outcome, final
  outcome must be incomplete or blocked, not complete.
- Keep list-only turns from reading contents.
- Treat unsupported binary/document formats as truthful limitations after
  checking target existence when possible.

## Acceptance Criteria

- `Read README.md` and `Read config.json` require successful read evidence or
  an explicit failure outcome.
- `Read .env` enters protected read approval before content can be disclosed.
- Denied protected read cannot leak content and cannot render complete.
- `List the files here, but do not read their contents` uses list-only evidence.
- Unsupported `.docx` read requests produce truthful unsupported capability
  output based on available evidence.
- Zero-tool `INSPECT_REQUIRED` or read-target-required answers do not complete.
- `VERIFY_ONLY` status questions such as `Is this BMI page working now?` require
  successful local evidence or an explicit not-verified/failed outcome.
- Repeated `WORKSPACE_ESCAPE`, sandbox, approval, or tool-loop failures count as
  unsatisfied evidence rather than as successful inspection.
- Prompt audit shows the evidence obligation.

## Tests / Evidence

Required deterministic regression:

- Unit test: evidence obligation derivation for read, protected read, list-only,
  workspace explain, unsupported document, and no-workspace turns.
- Executor/outcome test: read-target-required with zero tools is not complete.
- Executor/outcome test: verify-only web/status question with only failed
  escaped-path reads is not complete and records unsatisfied evidence.
- Permission test: protected read intent reaches approval/denial flow.
- TalosBench cases for config read, `.env` denial/approval, list-only, and
  unsupported document.

Manual/TalosBench rerun:

- Prompt family: `Read config.json...`, `Read .env...`,
  `Can you read report.docx and summarize it?`,
  `Is this BMI page working now?`
- Expected trace: evidence obligation present.
- Expected outcome: grounded answer, blocked protected read, or unsupported
  capability/not-verified note.

Commands:

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

Add broader gate before closeout:

```powershell
./gradlew.bat check --no-daemon
```

Hardening pass, 2026-04-30:

- Added runtime coverage that `VERIFY_ONLY` read-only status turns cannot render
  complete when verification remains `NOT_RUN`.
- Added the missing-evidence variant so a `VERIFY_ONLY` answer still says
  `not verified` even when the evidence obligation is unsatisfied.
- Re-ran TalosBench with the patched CLI:
  `local/manual-testing/talosbench/20260430-230044/summary.md`.
  Non-manual T57/T56/T58 smoke cases passed; approval-sensitive cases remained
  `MANUAL_REQUIRED`.

## Known Risks

- Evidence obligations can over-constrain broad Q&A if the policy treats every
  general question as file-read-required.
- Protected read approval must fail closed without leaking prompt or fixture
  content.

## Known Follow-Ups

- T58 centralizes final dominance over failed evidence obligations.
- Future document capability can add real extraction under a capability profile.

## Completion Evidence

- Implemented in `f2c1e54 T57: add evidence obligation policy`.
- Hardened in `f39d7e3 Hardening pass for T57 T58 T61`.
- Non-manual TalosBench evidence recorded in `local/manual-testing/talosbench/20260430-230044/summary.md`.
