# [T723-done-high] Private Document Capability Audit Scoring

Status: done
Priority: high

## Evidence Summary

- Source: `current-0.10.0-full-two-model-20260608-082529-docs` capability audit
- Date: 2026-06-08
- Talos version / commit: `0.10.0` / `6c05f8f0b34110faa80a04630a98cd9a2544510e`
- Model/backend: Qwen and GPT-OSS, installed product audit
- Raw transcript path: `local/manual-testing/current-0.10.0-full-two-model-20260608-082529-docs/LIVE-CAPABILITY-AUDIT-RESULTS.md`
- Related finding: `F-0.10-AUDIT-002`

Expected behavior:

```text
Private-mode PDF/DOCX/XLSX rows should distinguish local extraction evidence
from model-context handoff. A row can pass when the named document was locally
read or extracted, content was withheld from model context by policy, no raw
private markers leaked, and prompt-debug/provider-body evidence exists when
required.
```

Observed behavior:

```text
The audit script currently uses one expected_read_satisfied boolean. Private
withheld rows can report FAIL/PARTIAL even when the local extraction occurred
and the answer truthfully said the document text was withheld.
```

## Classification

Primary taxonomy bucket:

- `TRACE_REDACTION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `UNSUPPORTED_CAPABILITY`

Blocker level:

- release blocker

Why this level:

```text
This is release-evidence precision, not a confirmed content leak. It still
blocks release-gate closure because the audit packet cannot truthfully
distinguish extraction success, private-mode withholding, and real missing-read
failures.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The capability audit script has a lossy result schema. It collapses target
extraction, model handoff state, privacy withholding, unexpected sibling reads,
and prompt-debug evidence into one expected_read_satisfied flag.
```

Likely code/document areas:

- `scripts/run-capability-live-audit.ps1`
- `local/manual-testing/current-0.10.0-full-two-model-20260608-082529/FINDINGS.md`

## Goal

```text
Make capability audit rows explicitly report document target extraction,
model-context handoff state, unexpected private document targets, and
prompt-debug evidence so private-withheld rows can be scored accurately.
```

## Non-Goals

- No change to document extraction runtime.
- No change to private document model handoff policy.
- No release-gate closure in this ticket.
- No broad audit-runner rewrite.

## Implementation Notes

Add explicit fields:

- `expected_document_target`
- `document_target_read_or_extracted`
- `model_handoff_state`
- `unexpected_private_document_targets`
- `prompt_debug_saved`

Private local-display-only rows pass only when the named target was locally
read or extracted, the final answer says content was withheld, no private
markers leak, and required prompt-debug/provider-body evidence exists.

## Architecture Metadata

Capability:

- Document extraction audit scoring.

Operation(s):

- Audit transcript parsing and result scoring.

Owning package/class:

- `scripts/run-capability-live-audit.ps1`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: high release-evidence risk.
- Approval behavior: unchanged.
- Protected path behavior: audit-only scoring; must not expose raw content.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: prompt-debug/provider-body evidence must remain visible when required.
- Verification profile: audit script result classification.
- Repair profile: none.

Outcome and trace:

- Outcome/truth warnings: distinguish withheld-private-mode from missing read.
- Trace/debug fields: audit CSV/report fields only.

Refactor scope:

- Narrow script scoring helpers only.
- No product runtime rewrite.

## Acceptance Criteria

- Private PDF/DOCX/XLSX local-display rows pass when the named document was locally extracted/read, content was withheld by private mode, no private markers leaked, and prompt-debug/provider-body evidence exists.
- The audit output reports `model_handoff_state` separately from extraction/read success.
- Unexpected sibling private document reads are reported separately and can mark a row partial/fail without masking the named target result.
- Missing prompt-debug/provider-body evidence remains release-blocking when required.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Implemented:

- `scripts/run-capability-live-audit.ps1` now reports separate document target,
  target extraction/read, model handoff state, unexpected private document
  targets, and prompt-debug evidence fields.
- Private withheld rows are scored separately from missing target reads.
- Unexpected sibling private document targets are reported separately.

Verification evidence:

- `pwsh .\scripts\run-capability-live-audit.ps1 -SelfTest` passed.
- `.\gradlew.bat check --no-daemon` passed.

Required deterministic regression:

- Unit/self-test: private withheld PDF/DOCX/XLSX rows classify as pass when local extraction and withholding evidence are present.
- Unit/self-test: Qwen over-read shape reports `unexpected_private_document_targets`.
- Unit/self-test: missing prompt-debug/provider-body evidence remains blocking.

Commands:

```powershell
pwsh .\scripts\run-capability-live-audit.ps1 -SelfTest
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version.
- Keep T280/T284/T306/T312 open until full release-gate rerun passes.

## Known Risks

- PowerShell script tests may need a small self-test seam if none exists.

## Known Follow-Ups

- T280/T284/T306/T312 release-gate rerun after this cleanup batch.
