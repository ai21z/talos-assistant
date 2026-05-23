# File Support

This page answers: "Which files can Talos inspect or create safely?"

## Current Support

Talos is strongest for developer and text-oriented workspaces.

Strong support:

- text-oriented source code
- Markdown
- plain text
- JSON, YAML, XML, TOML, INI, properties
- HTML, CSS, and JavaScript when read as workspace text files
- CSV and TSV
- Gradle and Java project files

Default indexing is narrower than direct file reading. See
[Workspaces And Indexing](workspaces-and-indexing.md) before assuming a file
type is searchable through the index.

## Text-Bearing Documents

Talos has narrow local text extraction for:

- PDF files with extractable text
- `.docx` Word documents
- `.xls` and `.xlsx` workbooks

These are extraction paths, not layout-perfect document understanding.

Limits:

- scanned or image-only PDFs need a separate text-extraction step
- PDF reading order may be imperfect
- `.docx` comments, tracked changes, embedded objects, and layout fidelity are
  not guaranteed
- workbook formulas are not recalculated
- hidden sheets, charts, macros, and formatting are not a beta claim
- corrupt or encrypted files may be unreadable
- large extracted output may be truncated by runtime limits

## Unsupported Or Deferred Formats

Do not treat these as normal beta support:

- `.doc`
- `.ppt`
- `.pptx`
- archives such as `.zip`, `.tar`, `.gz`, `.7z`, `.rar`
- executables and compiled binaries
- visual analysis of images
- valid binary document generation

If Talos cannot extract content, the correct result is a refusal or extraction
status message, not a fabricated summary.

## Writing Files

Talos can write text-oriented files when approved.

Do not use Talos to create valid PDF, Word, spreadsheet, presentation, archive,
or executable files through the normal text-file tool surface.

Use Markdown, plain text, HTML, CSV, or another source format first, then use a
dedicated document tool outside Talos when a binary document is required.

## Private Documents

Even when extraction is technically available, sensitive personal paperwork is
not an approved beta product claim. Use private mode and avoid broad personal
folders.
