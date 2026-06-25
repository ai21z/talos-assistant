# T299 - Document Extraction Fixtures, BDD, and Live Audit

Status: done
Severity: high
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-17
Owner: unassigned

## Problem

The current live audit generates valid small PDF, DOCX, and XLSX fixtures. That is enough to prove parser/tool routing and two-model behavior for small generated beta-core fixtures, but it is not enough to prove real-world document quality. Images/OCR and PowerPoint are frozen for v1.

## Evidence from current code

- The e2e harness exists under `src/e2eTest/java/dev/talos/harness`.
- Private-mode scripted e2e coverage exists but is small: `src/e2eTest/java/dev/talos/harness/PrivateModeScriptedE2eTest.java:32`, `:46`.
- Unsupported final-answer tests are broad but simulate unsupported behavior, not successful extraction: `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java:90`, `:97`, `:104`, `:111`, `:118`, `:125`, `:131`, `:137`, `:166`.

## Evidence from tests/audits

The latest two-model beta-core capability audit ran 16 prompts per model and used generated valid PDF/DOCX/XLSX fixtures plus private-mode PDF/DOCX/XLSX ordinary-fact fixtures. Image/OCR and PowerPoint prompts were intentionally excluded. The broader historical 32-prompt T267 bank remains a runbook.

## User impact

Users cannot verify document support without known-good documents and repeatable expected outputs.

## Product risk

High. Weak fixtures produce false confidence and allow regressions in extraction quality, redaction, and artifact safety.

## Runtime boundary affected

Unit tests, integration tests, e2e harness, live prompt-bank audit, artifact scan, and release reports.

## Non-goals

- No model-quality benchmark beyond harness behavior.
- No broad legal/tax/medical correctness scoring.

## Required behavior

Add valid, deterministic fixtures:

- known-text PDF
- known-text DOCX
- known workbook XLSX
- known image with OCR target text for v1, not beta
- protected variants containing redaction canaries
- corrupt variants
- oversized/truncated variants
- optional encrypted/password-protected fixtures when parser support permits

Add BDD-style scenarios for user workflows:

- "summarize this PDF from extracted text"
- "compare DOCX with TXT"
- "find a value in XLSX"
- "OCR this image and state limitations" for v1, not beta
- "private mode blocks model handoff for protected extracted content"
- "artifact scan catches raw extracted canary leak"

## Proposed implementation

Create fixtures under test resources, not local manual folders. Add unit tests for adapters, integration tests for tools/RAG, e2e scripted scenarios for whole-turn behavior, and live prompt-bank additions for both models.

Fixture rule: keep tiny canonical binary fixtures checked into test resources where licensing and size allow it. Generator helpers are useful, but do not rely only on fixtures produced by the same parser library that the test is validating. At least one PDF, DOCX, XLSX, and OCR image fixture should be independently inspectable by a human and have exact expected extracted text checked into a neighboring text file.

## Tests

- adapter unit tests for each format
- `DocumentExtractionE2eTest`
- `DocumentExtractionArtifactCanaryScanTest`
- new JSON scenarios for PDF/DOCX/XLS/XLSX; image scenarios remain v1/open
- updated live prompt bank with extraction prompts

## Acceptance criteria

- Every beta document format has at least one valid safe fixture and one protected fixture.
- Tests prove exact expected extracted content, not just "non-empty output."
- Live audit captures tool calls, provider bodies, prompt-debug, traces, sessions, diffs, and artifact scan for extraction prompts.

## Rollback / migration notes

If a fixture exposes a library instability, keep that format disabled until the fixture passes consistently.

## Open questions

- Which larger adversarial fixtures, if any, belong outside the repo and are fetched only in optional/manual audit runs?

## Related files

- `src/e2eTest/java/dev/talos/harness/*`
- `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java`
- `scripts/run-t267-live-audit.ps1`

## 2026-05-16 Implementation update

Evidence note: beta-core live audit executed; fixture quality remains open. Image/OCR evidence is v1/open.

New evidence:

- `scripts/run-capability-live-audit.ps1` creates a fresh fixture workspace per model, runs GPT-OSS and Qwen, captures prompt-debug/provider bodies/diffs, and emits a summary CSV.
- Latest run: `capability-live-audit-20260516-210854`.
- The beta-core live audit passed 26/26 prompt runs by process/tool-artifact heuristics.
- Targeted `checkRuntimeArtifactCanaries` passed on the latest live audit roots.
- The generated audit report states that images and PowerPoint are frozen out of beta.
- Checked-in canonical fixtures now exist under `src/test/resources/document-fixtures/` for PDF, DOCX, and XLSX, each with a neighboring expected-text file consumed by `DocumentExtractionCanonicalFixturesTest`.
- `DocumentExtractionCanonicalFixturesTest` passed.

Remaining blockers:

- The live audit fixtures are still generated by the script; checked-in canonical fixtures cover parser smoke only, not live model behavior.
- Need checked-in protected PDF/DOCX/XLS/XLSX variants plus larger real-world fixtures.
- Need BDD/live prompts that explicitly cover formula cached-value wording and truncated/partial extraction.
- Image/OCR fixtures and real-OCR audit remain v1/open.

## 2026-05-17 Private-document artifact sink update

New deterministic sink tests now prove the configured ordinary private-document fact canary class is redacted by prompt-debug/provider-body rendering, session snapshots, turn JSONL, local trace JSON, memory persistence, and log/trace sanitizer helpers.

## 2026-06-07 0.10.0 beta-scope reconciliation

Scope decision: PDF/DOCX/XLS/XLSX extraction is beta scope for the `0.10.0`
decision, so the next full prompt-bank audit must include these formats. This
does not add image/OCR or PowerPoint to beta; those remain v1/deferred.

T299 remains open because generated and canonical fixtures are useful but not a
complete release-grade corpus. The next audit should at minimum prove current
small-fixture behavior for PDF/DOCX/XLSX under both Qwen and GPT-OSS, with
prompt-debug/provider-body/trace/session artifact canary review.

This deterministic sink suite did not replace live audit by itself. The later focused and private-folder live audits now use generated private-document fixtures containing ordinary private facts and run targeted artifact scanning over generated audit roots. Larger real-world fixture coverage remains open.

## 2026-05-17 Model-loop provenance update

Scripted model-loop tests now cover private-mode withholding for PDF, DOCX, XLS, and XLSX extraction. A scripted model answer that tries to restate a configured private-document fact canary after withheld extraction is redacted. Config-level document extraction send-to-model opt-in is covered with non-canary content.

Remaining live-audit work:

- Use fresh PDF/DOCX/XLSX private-fact fixtures per model.
- Save prompt-debug, provider-body, trace, session, turn JSONL, logs, diffs, and artifact-scan output.
- Verify the behavior with both standard local models, not only scripted tests.

## 2026-05-18 Focused two-model private-document audit update

Evidence note: focused beta-core live audit executed with generated private-document ordinary-fact fixtures. Fixture quality remains open. Image/OCR and PowerPoint evidence remains v1/open.

New evidence:

- `scripts/run-capability-live-audit.ps1` now generates private PDF, DOCX, and XLSX fixtures containing an ordinary private-document fact and adds private-mode prompts for those files.
- Latest run: `capability-live-audit-20260518-001437`.
- The beta-core live audit passed 32/32 prompt runs by process/tool-artifact heuristics.
- GPT-OSS and Qwen both read the private document targets and returned withheld-content answers instead of summarizing or revealing the private fact fixture.
- Targeted `checkRuntimeArtifactCanaries` passed on the latest live audit roots with source fixture files explicitly allowlisted.
- A direct artifact grep over generated model/runtime artifact directories found no raw private-document fact fixture values.

Remaining blockers:

- The live audit private-document fixtures are still generated by the script.
- Need larger/adversarial private PDF/DOCX/XLS/XLSX fixtures and checked-in or externally stored expected outputs.
- Need a broader private-folder prompt bank that covers approval denial, per-turn extracted-document send-to-model approval, RAG/reindex/retrieve behavior, `/show`, logs, traces, and session artifacts in one repeatable run.

## 2026-05-18 Private-folder bank update

Evidence note: scripted private-folder bank executed for non-interactive probes. Approval-sensitive probes still need a synchronized runner or human-operated transcript.

New evidence:

- `scripts/run-capability-live-audit.ps1` now supports `-PrivateFolderBank`.
- Latest private-folder bank run: `capability-live-audit-20260518-004603`.
- The bank ran 44 prompt turns across GPT-OSS and Qwen.
- Added probes cover private-mode `/show` for generated PDF/DOCX/XLSX fixtures, private-mode reindex refusal, private-mode retrieve-style behavior, and protected-read denial.
- Targeted `checkRuntimeArtifactCanaries` passed on the generated audit roots.
- The run generated `PRIVATE-FOLDER-MANUAL-AUDIT-RUNBOOK.md` for approval-sensitive cases not safe to automate with piped stdin.

Bug found:

- `/show` in private mode could read an existing index snippet if a prior developer-mode reindex had already indexed the file. That undermined the intended local-display extraction evidence.
- `ShowCommand` now skips Lucene snippets in private mode unless private-mode RAG is explicitly enabled.

Remaining blockers:

- Larger real-world/private fixtures.
- Approval grant/deny transcript capture.
- Per-turn extracted-document send-to-model approval UX/tracing.

## 2026-06-08 Post-T734 beta-core document evidence

The current candidate at commit `87f9fc1a019abbaba546e4264d111a0bb848a55b`
(`talosVersion=0.10.0`) ran the beta-core capability/private-mode bank:

```text
local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability
```

Results:

- 44 prompt turns across GPT-OSS and Qwen passed by process/tool-artifact
  heuristics.
- Public PDF/DOCX/XLSX prompts targeted `report.pdf`, `report.docx`, and
  `workbook.xlsx`, and recorded `SENT_TO_MODEL`.
- Private-mode PDF/DOCX/XLSX prompts targeted `private-report.pdf`,
  `private-report.docx`, and `private-workbook.xlsx`, and recorded
  `WITHHELD_PRIVATE_MODE`.
- XLS/text comparison used `workbook.xlsx` as the expected document target.
- The summary CSV recorded no unexpected private document targets, no raw
  secret leaks, no raw canary leaks, and no unsupported-overclaim flags.
- Targeted canary scan over the capability artifact and raw fixture roots
  passed with the script-generated fixture allowlist.
- Release-clean scan over model-facing artifacts and redacted capability
  snapshots passed without an allowlist.

Keep T299 open. This is valid small-fixture beta-core evidence for
PDF/DOCX/XLS/XLSX extraction, but the ticket still requires a larger maintained
fixture corpus, adversarial/private variants, and broader BDD-style document
quality evidence beyond generated small fixtures.

## 2026-06-08 WS5 provider-body quality review

New review report:

```text
work-cycle-docs/reports/current-0.10.0-ws5-provider-body-quality-review.md
```

The review confirms that, in the post-T734 capability packet, public
PDF/DOCX/XLSX rows sent extracted public fixture text to the model, while
private-mode PDF/DOCX/XLSX rows extracted the named private target locally and
withheld model handoff by default. `/show` private document rows remained
local-display-only and redacted the configured private value.

This strengthens the small generated beta-core document evidence. It does not
close T299 because the ticket still requires larger maintained/adversarial
fixtures and broader document-quality BDD coverage beyond generated tiny
fixtures.

## 2026-06-08 WS7 adversarial fixture regression slice

Added deterministic adapter coverage for corrupt beta-scope documents and XLS
parity limitations:

- `corrupt_pdf_reports_corrupt_not_generic_failed`
- `corrupt_docx_reports_corrupt_not_generic_failed`
- `xls_text_extraction_skips_hidden_sheets_and_reports_limitation`
- `xls_formula_cells_report_formula_and_cached_value_policy`
- `corrupt_xls_reports_corrupt_not_generic_failed`

The tests prove invalid PDF/DOCX files return `DocumentExtractionStatus.CORRUPT`,
blank `safeText`, a `document-corrupt` warning, and
`modelHandoffAllowed=false`. They now also prove corrupt `.xls` OLE2/header
failures classify as `CORRUPT`, hidden `.xls` sheets are skipped with an
`excel-hidden-sheets` limitation, and `.xls` formula cells report formula text
plus cached values with a non-recalculation warning. `DocumentExtractionService`
now classifies common PDF structural failure signals such as missing
root/trailer/xref/end-of-file and `.xls` invalid OLE2/header signatures as
corrupt rather than generic extraction failures.

Focused verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.extract.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon
```

Keep T299 open. This closes a small adversarial-classification and `.xls`
limitation-parity gap for generated fixtures, but it does not create the larger
maintained real-world fixture corpus, checked-in protected fixture set, or final
two-model live release packet required by the ticket.

## 2026-06-10 beta-scope narrowing decision

Current `0.10.1` evidence strengthens the narrow beta-core extraction claim:

- `local/manual-testing/current-0.10.1-release-packet-20260610-090049-capability`
  passed 44 turns across GPT-OSS and Qwen;
- public PDF/DOCX/XLSX prompts used the expected named targets and recorded
  `SENT_TO_MODEL`;
- private-mode PDF/DOCX/XLSX prompts used the expected named targets and
  recorded `WITHHELD_PRIVATE_MODE`;
- raw-root canary scan with the script allowlist passed;
- release-clean canary scan over model-facing artifacts and redacted snapshots
  passed.

Ticket decision:

- the narrow beta claim for small text-bearing PDF/DOCX/XLS/XLSX extraction is
  now supported by current-candidate evidence;
- the larger maintained/adversarial fixture corpus, broader BDD-style document
  quality coverage, and post-beta corpus work remain open and are explicitly
  deferred beyond beta.

Keep T299 open with that narrowed scope. Do not treat this ticket as a hard
blocker for the narrow `0.10.1` beta extraction claim.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as deferred out of this main-merge line: future private-document / document-beta / v1 / future-capability scope, not current main-merge work.

Closed by Opus as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
