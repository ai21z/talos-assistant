# T234 - Unsupported Binary Document Creation Must Not Write Fake Files

Status: done
Priority: high

## Evidence Summary

- Source: installed Talos live transcript from `C:\Users\arisz\Desktop\testtalos`
- Date: 2026-05-11
- Talos version / commit: installed `Talos 0.9.8`, build `2026-05-03T09:20:33.915042400Z`
- Model/backend: `qwen2.5-coder:14b`, legacy Ollama path
- Workspace fixture: empty desktop test workspace
- Approval choices: user approved writes

Observed behavior:

```text
User asked Talos to create a DOCX document.
Talos called talos.write_file on synthwave_band_webpage.docx with plain text.
User then asked Talos to delete that DOCX and make a PDF.
Talos attempted unknown talos.delete_file, then wrote synthwave_band_webpage.pdf
with placeholder plain text.
Adobe Acrobat could not open the fake PDF.
```

Expected behavior:

```text
Talos must not create fake .docx/.pdf/.xlsx/.pptx files using the text writer.
If valid binary document generation is unsupported, Talos should say so before
requesting approval or writing anything, and suggest a supported text/Markdown
source artifact instead.
```

## Classification

Primary taxonomy bucket:

- `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `TOOL_SURFACE`
- `OUTCOME_TRUTH`
- `PERMISSION`

Blocker level:

- release blocker

Why this level:

```text
This creates files with trusted binary-document extensions that are not valid
documents, then forces the user to discover the failure in another application.
That violates Talos's honesty and workspace-assistant standards.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model not to make fake PDFs.
```

Architectural hypothesis:

```text
Unsupported binary-document handling was implemented for reads and ingestion,
but not for writes or creation requests. The runtime still treats .docx/.pdf
targets as ordinary file-write targets, so model output and approval flow can
produce invalid binary-looking artifacts.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/ingest/UnsupportedDocumentFormats.java`
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`

Why a one-off patch is insufficient:

```text
The invariant belongs at both orchestration and tool boundaries: provider turns
should be avoided for known unsupported creation requests, and the write tool
must still reject unsupported binary extensions if a tool call reaches it.
```

## Goal

```text
Unsupported binary document creation requests produce a deterministic
capability-limited answer and no workspace mutation. talos.write_file rejects
unsupported binary-document extensions before creating fake files.
```

## Non-Goals

- No PDF, DOCX, XLSX, or PPTX generation support.
- No Apache POI, PDFBox, Tika, browser printing, or external converter.
- No delete-file tool in this ticket.
- No broad document pipeline.

## Implementation Notes

```text
Reuse the existing unsupported document format boundary. Add write/create
wording to that boundary, add a pre-approval write guard, and add a deterministic
turn-level preflight for natural-language requests such as "create a DOCX file"
or "make it PDF format" where no explicit filename is present.
```

## Architecture Metadata

Capability:

- Workspace file creation, unsupported binary document boundary

Operation(s):

- `write_file`

Owning package/class:

- `dev.talos.core.ingest.UnsupportedDocumentFormats`
- `dev.talos.tools.impl.FileWriteTool`
- `dev.talos.runtime.TurnProcessor`
- `dev.talos.cli.modes.AssistantTurnExecutor`

New or changed tools:

- No new tool
- `talos.write_file` gains an unsupported-format rejection

Risk, approval, and protected paths:

- Risk level: write
- Approval behavior: unsupported binary-document writes must fail before approval
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: no checkpoint for rejected unsupported writes
- Evidence obligation: unsupported capability answer, not read handoff
- Verification profile: no fake file to verify
- Repair profile: no automatic conversion

Outcome and trace:

- Outcome/truth warnings: no "created PDF/DOCX" prose after a rejected request
- Trace/debug fields: rejected write should be visible as unsupported format if a tool call reaches runtime

Refactor scope:

- Allowed: small helper policy for unsupported document mutation
- Forbidden: broad tool-surface redesign or delete tool implementation

## Acceptance Criteria

- Natural request to create a DOCX/PDF document returns a deterministic unsupported capability answer without provider/tool mutation.
- `talos.write_file` rejects `.pdf`, `.doc`, `.docx`, `.xls`, `.xlsx`, `.ppt`, and `.pptx` targets.
- Rejection happens before approval when the tool call reaches `TurnProcessor`.
- No placeholder PDF/DOCX files are written.
- Existing unsupported document read behavior remains unchanged.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `FileWriteToolTest` rejects unsupported binary document paths.
- Integration/executor test: unsupported DOCX/PDF creation requests return unsupported capability text and do not call the provider.
- Pre-approval test: unsupported `talos.write_file` call is rejected before approval.

Commands:

```powershell
./gradlew.bat test --tests "dev.talos.tools.impl.FileWriteToolTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
./gradlew.bat test --no-daemon
```

## Known Risks

- Some users may expect Talos to generate binary documents directly. Until a real document generator exists, producing Markdown/HTML/text source is safer than fake binary output.

## Known Follow-Ups

- A real document-generation capability can be designed later with a renderer/converter, binary validation, and format-specific verification.
- Delete-file support remains separate and should be designed with destructive-operation approval and checkpoint restore semantics.

## Completion Notes

Implemented on `v0.9.0-beta-dev`.

- Added `UnsupportedDocumentMutationPolicy` so natural requests such as
  "create a DOCX file" and "make it PDF format" return a deterministic
  unsupported-capability answer before any provider call, approval, checkpoint,
  or write.
- Added a `talos.write_file` hard guard for `.pdf`, `.doc`, `.docx`, `.xls`,
  `.xlsx`, `.ppt`, and `.pptx` targets.
- Added pre-approval validation so unsupported binary document writes are
  rejected before the user sees an approval prompt.
- Reused and extended the existing unsupported document boundary instead of
  introducing a fake document generator or broad tool-surface change.

Verification:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.impl.FileWriteToolTest.unsupportedBinaryDocumentWriteIsRejectedWithoutCreatingFakeFile" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*unsupported*" --no-daemon
.\gradlew.bat test --tests "dev.talos.tools.impl.FileWriteToolTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat test --no-daemon
.\gradlew.bat check installDist --no-daemon
```

Manual smoke with `build\install\talos\bin\talos.bat`:

```text
Prompt: create a docx file about a synthwave webpage
Result: deterministic unsupported Microsoft Word .docx answer; no file changed.

Prompt: delete the docx file and make the same thing in pdf format
Result: deterministic unsupported PDF/DOCX answer; no file changed.

Workspace after smoke: empty.
```
