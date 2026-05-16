# T299 - Document Extraction Fixtures, BDD, and Live Audit

Status: open
Severity: high
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

The current live audit generates valid small PDF, DOCX, and XLSX fixtures. That is enough to prove parser/tool routing and two-model behavior for small generated beta-core fixtures, but it is not enough to prove real-world document quality. Images/OCR and PowerPoint are frozen for v1.

## Evidence from current code

- The e2e harness exists under `src/e2eTest/java/dev/talos/harness`.
- Private-mode scripted e2e coverage exists but is small: `src/e2eTest/java/dev/talos/harness/PrivateModeScriptedE2eTest.java:32`, `:46`.
- Unsupported final-answer tests are broad but simulate unsupported behavior, not successful extraction: `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java:90`, `:97`, `:104`, `:111`, `:118`, `:125`, `:131`, `:137`, `:166`.

## Evidence from tests/audits

The latest two-model beta-core capability audit ran 13 prompts per model and used generated valid PDF/DOCX/XLSX fixtures. Image/OCR and PowerPoint prompts were intentionally excluded. The broader historical 32-prompt T267 bank remains a runbook.

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

Status: beta-core live audit executed; fixture quality remains open. Image/OCR evidence is v1/open.

New evidence:

- `scripts/run-capability-live-audit.ps1` creates a fresh fixture workspace per model, runs GPT-OSS and Qwen, captures prompt-debug/provider bodies/diffs, and emits a summary CSV.
- Latest run: `capability-live-audit-20260516-195820`.
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


