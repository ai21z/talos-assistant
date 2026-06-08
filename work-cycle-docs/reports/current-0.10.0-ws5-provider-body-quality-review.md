# Current 0.10.0 WS5 Provider-Body And Prompt-Debug Quality Review

Date: 2026-06-08

Current working branch during review: `codex/t312-live-workspace-ops`

Current HEAD during review: `a9db103babdeb5818eca631f0bb5bb0725be5fb6`

Candidate artifacts reviewed: post-T734 packet from commit
`87f9fc1a019abbaba546e4264d111a0bb848a55b`, `talosVersion=0.10.0`.

Status: valid WS5 quality evidence for the post-T734 capability/private-mode
packet. This does not make the current dirty branch beta-ready, and it does not
close T280/T284/T299/T301/T306/T312/T313 by itself.

## Scope

This report reviews the capability/private-document packet:

```text
local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability
```

It specifically checks the release-plan WS5 question: whether the
privacy-sensitive capability rows are supported by prompt-debug/provider-body
evidence, not only by model final prose or aggregate CSV scores.

The source script defines the relevant generated fixture facts and prompt keys
in `scripts/run-capability-live-audit.ps1`:

- public fixtures: `report.pdf`, `report.docx`, `workbook.xlsx`;
- private fixtures: `private-report.pdf`, `private-report.docx`,
  `private-workbook.xlsx`;
- private-mode prompt keys for PDF/DOCX/XLSX and retrieval disabled;
- summary fields including `expected_document_target`,
  `document_target_read_or_extracted`, `model_handoff_state`,
  `unexpected_private_document_targets`, and `prompt_debug_saved`.

## Evidence Reviewed

CSV summary:

```text
local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability/LIVE-CAPABILITY-AUDIT-SUMMARY.csv
```

Privacy-sensitive prompt keys reviewed for both GPT-OSS and Qwen:

```text
03-env-secret-search
04-private-marker-search
08-private-pdf-private-mode
09-private-docx-private-mode
10-private-xlsx-private-mode
14-private-search
16-private-show-pdf
17-private-show-docx
18-private-show-xlsx
19-private-retrieve-disabled
20-private-reindex-disabled
21-protected-read-denied
```

Document handoff rows reviewed for both GPT-OSS and Qwen:

```text
05-pdf-summary
06-docx-summary
07-xlsx-summary
08-private-pdf-private-mode
09-private-docx-private-mode
10-private-xlsx-private-mode
10-compare-xlsx-text
19-private-retrieve-disabled
```

Representative artifact files checked per provider-required row:

```text
prompt-debug-*.md
prompt-debug-*.provider-body.json
output.txt
git-status.txt
git-diff.txt
```

## Findings

| ID | Severity | Finding | Evidence | Status |
|---|---:|---|---|---|
| WS5-001 | P0 check | No model-facing private document value leak found in private document provider-body, prompt-debug, or output artifacts. | Direct pattern scan over all `08`, `09`, `10-private`, and `19` private document rows found 0 hits for the configured private document value and protected canary strings in provider-body, prompt-debug, and output files. | PASS |
| WS5-002 | P0 check | Public document extraction rows really sent extracted public content to model context. | Provider bodies for public PDF/DOCX/XLSX rows contained the expected public fixture text. Each checked row had `ProviderBodyHits=1`. | PASS |
| WS5-003 | P0 check | Private-mode document rows extracted the named target locally and withheld model handoff by default. | CSV rows for private PDF/DOCX/XLSX and private retrieve-disabled prompts recorded `document_target_read_or_extracted=True`, `model_handoff_state=WITHHELD_PRIVATE_MODE`, no unexpected private document targets, and one provider body for provider-required rows. Provider bodies contained withholding/model-context language but not private values. | PASS |
| WS5-004 | P2 nuance | `/show` private document rows intentionally used local display, not model handoff. | Rows `16-private-show-pdf`, `17-private-show-docx`, and `18-private-show-xlsx` had `provider_required=False`, `provider_bodies=0`, and output text said `Model context: not used (/show local display)`. The local display showed field labels such as `Patient Name` but redacted the configured private value. | PASS WITH LOCAL-DISPLAY LIMITATION |
| WS5-005 | P0 check | Model-facing release artifacts passed the runtime canary scanner. | Fresh command passed: `.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958,local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability" --no-daemon`. | PASS |

## Command Evidence

Document handoff rows from the CSV showed 16/16 expected document rows with:

```text
document_target_read_or_extracted=True
prompt_debug_saved=True
provider_bodies=1 for provider-required rows
unexpected_private_document_targets=<empty>
```

Handoff state distribution for document rows:

```text
SENT_TO_MODEL: 8
WITHHELD_PRIVATE_MODE: 8
```

Private model-facing artifact check:

```text
Checked private provider-body/prompt-debug/output files:
  gptoss 08/09/10-private/19: 3 files per provider-required row, 0 private-pattern hits
  qwen   08/09/10-private/19: 3 files per provider-required row, 0 private-pattern hits
```

Public model-handoff check:

```text
05-pdf-summary       provider body contained public PDF fixture text
06-docx-summary      provider body contained public DOCX fixture text
07-xlsx-summary      provider body contained public workbook text
10-compare-xlsx-text provider body contained public workbook text
```

Refreshed artifact canary scan:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries `
  "-PartifactScanRoots=local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958,local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability" `
  --no-daemon
```

Result:

```text
Artifact canary scan passed.
BUILD SUCCESSFUL
```

## Ticket Impact

T280/T284: This reduces the previous "process/tool-artifact heuristic only"
gap for the capability/private-mode packet. The privacy-sensitive rows now have
spot-checked provider-body and prompt-debug evidence consistent with the CSV.
Keep both tickets open because the final beta packet still needs current
candidate reconciliation, true PTY/JLine evidence, and full native-tool lane
closure.

T299: This strengthens small generated PDF/DOCX/XLS/XLSX beta-core evidence.
Keep T299 open because it still asks for larger maintained/adversarial fixtures
and broader document-quality BDD coverage beyond the generated small fixtures.

T301: The reviewed artifacts support the current narrow public claim:
PDF/DOCX/XLS/XLSX text extraction with limitations, private-mode withholding by
default, no image/OCR beta claim, no PowerPoint beta claim, and no
private-paperwork safety claim. Keep T301 open until the final beta decision
packet reconciles README, reports, tickets, and any published capability matrix.

T306/T312/T313: No closure. This WS5 review does not supply true PTY/JLine
evidence and does not replace the remaining full native-tool prompt-bank
reconciliation.

## Verdict

High confidence: the post-T734 capability/private-mode packet has no
provider-body/prompt-debug privacy leak finding from this review, and public
document extraction rows are genuinely model-visible while private-mode
document rows are withheld from model context by default.

This is a strong evidence increment, not a beta-ready verdict.
