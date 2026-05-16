# T294 - Local Image OCR Extraction

Status: open - frozen out of beta, v1 candidate
Severity: high
Release gate: no for beta; yes for any v1 image/OCR claim
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Talos now has a local OCR command adapter and preflight/status visibility, but production image support is not closed. Images are frozen out of beta. Future image support should mean local OCR with explicit limitations, not visual hallucination. A controlled stub proves routing and artifact boundaries; it does not prove real OCR quality.

## Evidence from current code

- Image formats are classified as OCR-capable only when `document_extraction.image_ocr.enabled=true`: `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`.
- `DocumentExtractionService` invokes a configured local OCR command with bounded timeout/output and sanitized extracted text.
- `DocumentExtractionPreflight` reports whether OCR is disabled, unavailable, or available based on config/command resolution.
- `ReadFileTool`, grep, slash `/grep`, and `Indexer` route image OCR through the shared extraction path rather than ad hoc image handling.

## Evidence from source crosscheck

Tesseract documents command-line OCR usage. Apache Tika can integrate OCR flows, but OCR quality is inherently variable and dependency-sensitive.

## User impact

Users can only ask Talos to inspect image text when a local OCR command is configured and working. Without that command, Talos must report OCR unavailable. It still cannot understand scenes, objects, signatures, or visual layout.

## Product risk

High for v1. OCR can be wrong, slow, language-dependent, and sensitive. False OCR confidence is dangerous for tax, health, legal, or identity documents.

## Runtime boundary affected

Image OCR, command execution, OCR stdout/stderr, extracted text, model context, prompt-debug, provider-body, traces, sessions, RAG indexes, and final-answer confidence.

## Non-goals

- No general computer vision scene understanding.
- No remote image analysis.
- No handwritten-text guarantee.
- No identity-document verification claim.

## Required behavior

- Detect supported image formats.
- Run local OCR only when the configured OCR provider is available and allowed.
- Return extracted text plus OCR warnings/confidence when available.
- Report "OCR unavailable" or "no text extracted" without inventing visual content.
- Enforce file size, image dimension, timeout, and output-size limits.
- Redact OCR text before model context/artifacts.

## Proposed implementation

Implement an image OCR adapter behind T290's extraction interface. Use a local Tesseract command adapter with strict command construction, no shell string concatenation, bounded timeout, bounded output, and sanitized logs. OCR should be disabled unless detected/configured.

Product decision: images are frozen out of beta. Talos must not claim image understanding or beta image/OCR support. It can claim OCR text extraction only in a future v1 scope after fixture tests, real provider preflight, and live audit pass.

## Tests

- `image_ocr_reads_known_png_text_when_tesseract_available_or_stubbed`
- `image_ocr_unavailable_reports_honestly`
- `image_ocr_output_redacts_secret_like_text`
- `protected_image_private_mode_does_not_enter_model_context`
- `image_ocr_artifacts_do_not_contain_raw_canary`
- `large_image_rejected_or_downscaled_by_policy`
- `image_without_text_reports_no_text_without_scene_claim`
- `image_answer_does_not_describe_visual_scene_without_ocr_text`
- `ocr_command_args_are_built_without_shell_concatenation`
- `image_rag_indexing_uses_sanitized_ocr_text_only_when_enabled`

## Acceptance criteria

- Talos can extract text from known fixture images in controlled local tests.
- Talos does not claim to see objects, people, signatures, or forms unless OCR text supports the answer.
- OCR dependency absence is handled cleanly.
- Product copy distinguishes OCR text extraction from general visual analysis.

## Rollback / migration notes

OCR can remain disabled by default until installers/configuration are stable. If disabled, current honest refusal remains.

## Open questions

- Which local OCR provider/preflight should v1 support?
- Which languages ship as default OCR language assumptions?

## Related files

- `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- `src/main/java/dev/talos/runtime/command/ProcessCommandRunner.java`
- `src/main/java/dev/talos/runtime/command/CommandArgumentPolicy.java`

## 2026-05-16 Implementation update

Status: OCR command adapter and preflight visibility implemented; image/OCR is frozen out of beta and remains a v1 issue.

Code evidence:

- `DocumentExtractionService` can run a configured local OCR command with bounded timeout/output.
- `FileCapabilityPolicy` classifies images as OCR-capable only when `document_extraction.image_ocr.enabled=true`.
- Default config keeps `document_extraction.image_ocr.enabled=false`.
- `TaskContractResolver` and evidence gates now treat image filenames as named read targets when OCR is enabled.
- `DocumentExtractionPreflight` reports Image OCR as disabled, unavailable, or available without executing arbitrary configured commands.
- `/status --verbose` surfaces the document-extraction preflight so users/maintainers can see whether Image OCR is actually backed by a resolved local command.
- `scripts/run-capability-live-audit.ps1` now distinguishes controlled OCR stub mode from `-UseRealOcr`; real-OCR mode blocks if no OCR command resolves.

Verification:

- `DocumentExtractionAdaptersTest` passed using a controlled local OCR command.
- `DocumentExtractionPreflightTest` passed.
- `InfraCommandsTest` status coverage passed for document-extraction preflight output.
- Full `./gradlew.bat clean check e2eTest --no-daemon` passed.
- Earlier two-model live audit `capability-live-audit-20260516-175600` passed `08-image-summary` with a configured local OCR stub and reported that caveat. The latest beta-core live audit intentionally excludes image prompts.
- Real-OCR preflight `scripts/run-capability-live-audit.ps1 -UseRealOcr -PreflightOnly` blocked because no local OCR command was found.

Remaining blockers:

- Production Tesseract or equivalent OCR provider is not installed/configured in this environment.
- Need independent image fixtures, language handling, confidence/no-text behavior, large-image limits, and scanned-PDF routing.
- Need a successful `-UseRealOcr` two-model audit before claiming v1 image OCR readiness.
- Do not claim visual image understanding.
