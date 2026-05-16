# T269 - User-Facing File Capability Matrix and Beta Warning

Status: open
Severity: high
Release gate: yes - product copy and beta positioning
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Talos documentation must clearly say what Talos can and cannot handle. Without a capability matrix, users may assume sensitive-paperwork, image/OCR, PowerPoint, or full-fidelity document support that does not exist yet.

## Evidence from current code

Talos supports strong local code/text/config workflows, local text extraction for text-bearing PDFs, DOCX, and XLS/XLSX, plus hardened indirect-read privacy paths. Image/OCR and PowerPoint are frozen out of beta and must stay documented as v1/open work.

## Evidence from external/source crosscheck

Codex/Gemini comparisons reinforce that clear permission/tool boundaries and transparent capabilities matter. Instructions and docs are not security boundaries, but they prevent product overclaiming.

## User impact

End users may put tax, health, legal, family, admin, or private project folders into Talos before the runtime proves those folders are safe.

## Product risk

Overclaiming sensitive-document readiness before gates pass creates a trust failure even if core developer workflows work well.

## Runtime boundary affected

Documentation and user expectation boundary.

## Non-goals

- Marketing copy.
- Claiming private paperwork readiness before T267/T268/T270/T271/T272 gates pass.

## Required behavior

Docs must state:

- Good now: code projects, Markdown/plain text notes, JSON/YAML/config/source files, CSV/TSV, static websites, PDF/DOCX/XLS/XLSX text extraction with limitations, local developer workflows, non-sensitive workspace folders.
- Supported text formats: Markdown, text, JSON/YAML/XML/TOML/INI/config, CSV/TSV, HTML/CSS/JS/TS, Java/Kotlin/Python/Go/Rust/C/C++ headers, scripts, Gradle/Dockerfile/README/LICENSE/project files.
- Supported document extraction with limitations: text-bearing PDFs, DOCX, XLS, XLSX. Excel formula cells expose formula text plus cached display value when available; formulas are not recalculated. Large extracted output can be partial/truncated.
- Frozen for v1/open issue: images/scans/OCR and PowerPoint.
- Unsupported/not-yet-extractable: legacy `.doc`, archives, most binaries, and arbitrary visual/layout understanding.
- Before all privacy gates pass, Talos must not be positioned as safe for tax/health/legal/family/admin paperwork.

## Proposed implementation

Create/update a capability matrix in README or docs and add a release-gate report summary.

## Tests

Documentation review plus release-gate report checklist.

## Acceptance criteria

- Capability matrix exists.
- Sensitive-paperwork warning exists.
- Forbidden claims are absent.

## Rollback / migration notes

None.

## Open questions

- Where should the canonical user-facing capability matrix live long-term: README, docs/release, or both?

## Related files

- `README.md`
- `docs/architecture/*`
- `docs/evaluation/*`
- `docs/release/beta-readiness.md`
- `work-cycle-docs/reports/t267-and-file-format-release-gate.md`
