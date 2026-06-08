# T301 - Document Capability Docs and Release Claims

Status: still-open - capability docs exist but release-claim drift prevention remains open
Severity: high
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Docs and release reports must evolve as extraction is added. Current docs must state exactly what is supported, partial, disabled, frozen, or still unsupported.

## Evidence from current code/docs

- README currently states Talos has narrow local text extraction for PDF, DOCX, and XLS/XLSX.
- README states images and PowerPoint are frozen out of beta and tracked for v1.
- README forbids private paperwork positioning until gates pass.
- Work-cycle reports still include stale statements that the full prompt bank was not run, while local evidence now shows a later two-model run completed.

## Evidence from tests/audits

The source-backed local review records the corrected state: a two-model beta-core capability live audit ran, but private-document release remains blocked by missing private-paperwork fixtures, adversarial document quality evidence, and remaining private-document gates.

## User impact

Wrong docs will either undersell completed extraction or, worse, overclaim private-document safety.

## Product risk

High. Release copy can create false trust even if code is honest.

## Runtime boundary affected

README, release reports, tickets, capability matrix, `/privacy help`, `/status`, live audit reports, and final product positioning.

## Non-goals

- No marketing copy.
- No tax/health/legal advice claims.

## Required behavior

Docs must distinguish:

- current supported text formats
- implemented extraction formats
- frozen image/OCR behavior
- unsupported PPT/archive/binary behavior
- private mode versus developer mode
- model-context and artifact persistence risks
- live audit status
- `.docx` from legacy `.doc` if only DOCX is implemented
- `.xlsx` from `.xls`, `.xlsm`, and `.xlsb` if only XLSX is implemented
- OCR text extraction from visual image understanding
- text PDF extraction from scanned PDF OCR

## Proposed implementation

Update README and release reports only after each extractor passes deterministic tests, artifact scan, and two-model live prompt-bank checks. Add a table-driven capability matrix and keep forbidden claims explicit.

Add a stale-report cleanup step whenever extraction support changes. Historical reports may remain as dated evidence, but the current release gate report and README must not contain contradictory current-state claims.

## Tests

- `ReadmePrivacyCopyTest`
- docs tests that assert supported/unsupported claims match the enabled format adapters
- release-report grep checks for stale "live audit not run" claims after results are updated

## Acceptance criteria

- No doc says Talos can read a format before the adapter is implemented and tested.
- No doc says private-document beta is ready until privacy, extraction, RAG, artifact, and live audit gates pass.
- Stale release reports are reconciled with the latest local audit evidence.

## Rollback / migration notes

If an extractor is disabled after a regression, docs must immediately return that format to unsupported/partial wording.

## Open questions

- Should capability docs be generated from config/test evidence to reduce drift?

## Related files

- `README.md`
- `work-cycle-docs/reports/*.md`
- `src/test/java/dev/talos/docs/ReadmePrivacyCopyTest.java`

## 2026-05-16 Implementation update

Evidence note: README and current release reports updated; keep open for drift prevention.

Current allowed wording:

- PDF text extraction with layout/order limitations.
- DOCX text extraction with structure/layout limitations.
- XLS/XLSX visible cell extraction without formula recalculation; formula cells show formula text plus cached display value when available.
- Large extracted output can be partial/truncated and must be described that way.
- Images/OCR frozen for v1; no beta image/OCR claim.
- `/status --verbose` reports document-extraction preflight, including Image OCR command availability.
- `scripts/run-capability-live-audit.ps1 -BetaCoreOnly -PrivateFolderBank` is the current focused private-folder audit mode and excludes image/PPT prompts.

Current forbidden wording:

- Private tax/health/legal/family/admin folder safety.
- Generic private-document readiness.
- Visual image understanding.
- PowerPoint reader.
- Global guarantee that protected content never reaches model context.

Evidence:

- README current status section was updated in this pass.
- `full-talos-capability-state-and-document-extraction-audit.md` is the current superseding report.
- Latest focused private-folder bank audit is `capability-live-audit-20260518-004603`.
- Checked-in canonical PDF/DOCX/XLSX fixtures with expected-text files are covered by `DocumentExtractionCanonicalFixturesTest`.
- Older reports may remain as dated evidence but should not be used as the current release decision.

## 2026-05-20 Update

README now has an explicit `Capability Matrix` separating:

- supported developer/text workspace work;
- PDF/DOCX/XLS/XLSX text extraction;
- unsupported PDF/DOCX/XLS/XLSX binary generation;
- frozen Image/OCR and PowerPoint beta claims;
- private-paperwork warnings.

Regression coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.docs.ReadmePrivacyCopyTest" --no-daemon
```

T269 and T320 are closed by this matrix/test slice. Keep T301 open only for broader release-report drift prevention and any future generated/docs consistency checks.

## 2026-06-07 0.10.0 beta-scope reconciliation

Scope decision: PDF/DOCX/XLS/XLSX extraction is in scope for the `0.10.0` beta
decision. Current release/audit wording must therefore distinguish:

- beta text extraction for PDF/DOCX/XLS/XLSX with limitations;
- no private-paperwork safety claim;
- no image/OCR beta claim;
- no PowerPoint beta claim;
- no browser/render-proof claim for static web unless a browser audit supplies
  that evidence.

T301 remains open to verify the release packet and live-audit reports do not
drift from that scope.

## 2026-06-08 Post-T734 release-claim evidence

The current candidate evidence is aligned with the existing beta-scope wording:

- PDF/DOCX/XLS/XLSX text extraction is included in the
  `current-0.10.0-release-packet-post-t734-20260608-191958-capability`
  audit.
- Image/OCR and PowerPoint are explicitly excluded from that audit and remain
  frozen out of beta claims.
- Private-mode document extraction rows record `WITHHELD_PRIVATE_MODE` for the
  named private PDF/DOCX/XLSX targets.
- The capability report still says the pass is based on
  process/tool-artifact heuristics and requires maintainer review for
  prompt-debug/provider-body quality.
- No evidence in this packet supports private-paperwork safety, visual image
  understanding, PowerPoint extraction, or browser/render-proof claims.
- Release-clean artifact scan passed over model-facing artifacts and redacted
  snapshots.

Keep T301 open. The latest packet supports the current narrow beta extraction
wording, but the broader release-claim drift-prevention work remains open until
the final beta decision packet reconciles README, reports, tickets, and any
published capability matrix.


