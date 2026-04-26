# [done] Ticket: Unsupported Binary Document Honesty
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`

## Why This Ticket Exists

The owner asked what Talos can manually handle today, including PDFs, docs, and
Excel files.

Manual installed-Talos QA against a workspace with fake `sample.pdf` and
`sample.xlsx` produced an answer that was mostly safe, but not precise enough:

```text
sample.pdf and sample.xlsx: Do not contain any extractable text.
These files are empty or do not contain any readable text.
```

The safer claim is:

```text
Talos does not currently have first-class PDF/XLSX extraction in this tool
surface, so it cannot inspect those binary document contents directly.
```

## Problem

Talos's current tool surface is text-workspace oriented:

- `talos.read_file` reads files as text through `Files.readAllLines(...)`.
- `talos.grep` skips binary-looking files.
- `ParserUtil` rejects binary/unsupported files during ingestion.
- default config excludes PDFs and does not include Office document formats.
- there is no PDFBox/Tika/Apache POI dependency.

When the model sees failed or skipped binary reads, it may phrase the result as
a fact about the document contents rather than a capability limitation.

That is a trust issue. Talos should distinguish:

- "I inspected this text file and found X"
- "This binary format is unsupported by current tools"
- "The file appears empty"

## Goal

Make unsupported binary document handling explicitly capability-based and
honest in tool results and final answers.

## Scope

### In scope

- Detect common unsupported binary document extensions:
  - `.pdf`
  - `.doc`
  - `.docx`
  - `.xls`
  - `.xlsx`
  - `.ppt`
  - `.pptx`
- Return clear tool errors or warnings that say the format is unsupported by
  current Talos text tools.
- Adjust prompt/tool guidance if needed so the model does not infer "empty" or
  "no extractable text" from unsupported reads.
- Add tests for binary document honesty.

### Out of scope

- Adding PDF extraction.
- Adding Office document extraction.
- Adding Apache Tika/PDFBox/POI dependencies.
- OCR or image extraction.
- Cloud parsing services.

## Proposed Work

1. Add an extension-aware unsupported document check near file-read and/or
   ingestion boundaries.

   Candidate places:

   ```text
   src/main/java/dev/talos/tools/impl/ReadFileTool.java
   src/main/java/dev/talos/core/ingest/ParserUtil.java
   ```

2. Return a clear, model-consumable message:

   ```text
   Unsupported binary document format: sample.pdf. Talos cannot extract PDF
   text with the current local text-tool surface.
   ```

3. Ensure final-answer shaping does not overstate document facts after an
   unsupported-read result.

4. Add tests:

   - `read_file(sample.pdf)` reports unsupported format, not empty content
   - `grep`/retrieval behavior stays safe
   - an assistant answer about a PDF says capability limitation, not content
     certainty

## Likely Files / Areas

- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
- `src/main/java/dev/talos/core/ingest/ParserUtil.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/tools/impl/ReadFileToolTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.tools.impl.ReadFileToolTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Manual installed verification:

- Use a disposable workspace with `notes.txt`, `sample.pdf`, and
  `sample.xlsx`.
- Ask Talos to summarize the workspace documents.
- Expected answer:
  - summarizes `notes.txt`
  - states PDF/XLSX extraction is unsupported
  - does not claim the binary files are empty or contain no extractable text

## Acceptance Criteria

- Unsupported binary document formats are reported as unsupported capability,
  not as empty/readable content facts.
- Talos remains local-first and dependency-light.
- No new binary extraction dependency is introduced without a separate
  architecture decision.

## Completion Notes

Implemented on branch `ticket/talos-unsupported-binary-document-honesty`.

- Added an explicit unsupported binary document capability boundary for
  `.pdf`, `.doc`, `.docx`, `.xls`, `.xlsx`, `.ppt`, and `.pptx`.
- `talos.read_file` now returns `UNSUPPORTED_FORMAT` with capability-based
  wording before trying to treat these formats as text.
- Ingestion rejects those formats with the same capability-based message if a
  custom config ever includes them.
- `talos.grep` reports skipped unsupported binary documents when the user
  explicitly searches an unsupported include glob.
- End-of-turn outcome shaping removes unsupported-document "empty/no readable
  text" claims after unsupported read failures and prepends a capability note.
- Added deterministic E2E coverage in
  `32-unsupported-binary-document-honesty.json`.

Verification:

```powershell
./gradlew.bat test --tests "dev.talos.tools.impl.ReadFileToolTest" --tests "dev.talos.tools.impl.GrepToolTest" --tests "dev.talos.core.ingest.ParserUtilSmokeTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.unsupportedBinaryDocumentHonesty"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
pwsh tools/uninstall-windows.ps1 -Quiet
./gradlew.bat --no-daemon installDist
pwsh tools/install-windows.ps1 -Force -Quiet
```

Installed Talos manual verification against
`local/manual-testing/qa-workspaces/binary-docs` produced an answer that
summarized `notes.txt` and said Talos is unable to inspect or extract text from
`sample.pdf` and `sample.xlsx`; it did not call the files empty.
