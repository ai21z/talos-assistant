# Full Talos Capability State and Document Extraction Audit

Generated: 2026-05-16

Updated: 2026-05-18

Branch: `v0.9.0-beta-dev`

Latest live audit id: `capability-live-audit-20260518-004603`

## 1. Executive Verdict

Verdict: developer/text-project beta candidate after maintainer trace review, not private-document beta.

Confidence: moderate-high for the implemented code/test state; moderate for real-world PDF/DOCX/Excel document quality; moderate for the focused generated-fixture private-document provenance path; low for broad private-document readiness.

The hard truth: Talos is stronger than it was at the start of this cycle, but it is not yet a serious private-paperwork product. It can now extract text from text PDFs, DOCX, XLS, and XLSX, and those beta-core paths passed a two-model capability audit plus targeted artifact scan. The latest private-folder bank also proves generated PDF/DOCX/XLSX fixtures are read/displayed through private-mode boundaries, that `/show` no longer uses stale index snippets when private-mode RAG is disabled, and that private-mode reindex/retrieve-style probes fail closed by default. That is enough for a developer/text-project beta candidate and stronger private-document direction, not enough for an automatic private-document release call. Maintainer trace review, larger real-world fixtures, and explicit send-to-model UX evidence are still required. Images and PowerPoint are frozen out of beta and remain v1/open issues. Private tax, health, legal, and family/admin positioning is still forbidden.

## 2. Source-Crosschecked Technical Basis

| Source | Relevant evidence | Talos decision |
|---|---|---|
| Apache PDFBox official getting-started docs | Latest dependency shown as `org.apache.pdfbox:pdfbox:3.0.7`. Source: https://pdfbox.apache.org/3.0/getting-started.html | `gradle.properties` now pins `pdfboxVersion=3.0.7`; provenance uses loaded library metadata, not a hardcoded version. |
| Apache POI official download page | Latest stable release is Apache POI 5.5.1, Maven artifacts use group `org.apache.poi` and version `5.5.1`. Source: https://poi.apache.org/download.html | `gradle.properties` pins `poiVersion=5.5.1`; DOCX/XLS/XLSX adapters use POI. |
| Apache POI Word component docs | XWPF is the DOCX API; POI itself says support is strong for some text-extraction use cases and incomplete for others. Source: https://poi.apache.org/components/document/index.html | Talos docs must say DOCX text extraction, not perfect Word document review. Legacy `.doc` remains deferred. |
| Tesseract command-line usage | Basic OCR invocation is command-line based; language and tessdata setup matter. Source: https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html | Talos implements a bounded local OCR command adapter, but image/OCR is frozen out of beta and needs v1 setup/preflight. |
| Apache Log4j installation docs | `log4j-to-slf4j` is the bridge translating Log4j API calls to SLF4J; missing provider errors are documented behavior. Source: https://logging.apache.org/log4j/2.x/manual/installation.html#impl-core-bridge-slf4j | Added `log4j-to-slf4j` runtime dependency so POI/PDFBox transitive Log4j API use does not print provider errors to the CLI. |

## 3. What Changed

Implemented:

- Added a central `DocumentExtractionService` at `src/main/java/dev/talos/core/extract/DocumentExtractionService.java`.
- Added structured extraction result/provenance/status types under `src/main/java/dev/talos/core/extract/`.
- Added PDF text extraction through PDFBox 3.0.7.
- Added DOCX text extraction through POI XWPF.
- Added XLS and XLSX visible-cell extraction through POI HSSF/XSSF.
- Added checked-in canonical PDF/DOCX/XLSX fixtures under `src/test/resources/document-fixtures/`, with neighboring expected-text files consumed by tests.
- Added workbook formula-cell output as formula text plus cached display value when available; formulas are not recalculated.
- Added explicit `PARTIAL` status and `extraction-truncated` warning when extracted text exceeds the current character cap.
- Added an experimental image OCR path through a bounded local OCR command adapter, but images are frozen out of beta.
- Added document-extraction preflight visibility in `/status --verbose`; Image OCR now reports disabled, unavailable, or available without executing the OCR command.
- Added extraction-aware file capability states in `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`.
- Routed `ReadFileTool`, native grep, slash `/grep`, and RAG indexing through extraction-aware policy.
- Added document extraction policy metadata to RAG indexes through `Indexer`.
- Added config defaults under `document_extraction` in `default-config.yaml` and `Config.ensureDefaults`.
- Added config-aware evidence gating so enabled PDF/DOCX/XLS/XLSX targets are actually read before answer synthesis; image gating remains future/v1 because images are frozen out of beta.
- Added partial multi-target evidence recovery so compare flows do not silently read only one side.
- Added a two-model capability live-audit script: `scripts/run-capability-live-audit.ps1`, including explicit controlled-stub versus `-UseRealOcr` modes.
- Added Log4j-to-SLF4J bridge to remove user-visible Log4j provider errors from document extraction runs.
- Added focused private-mode live-audit prompts for generated PDF/DOCX/XLSX fixtures containing ordinary private-document facts.
- Added a `-PrivateFolderBank` live-audit mode covering `/show`, private-mode reindex/retrieve-style behavior, and protected-read denial probes, plus a generated manual runbook for approval-sensitive cases.

## 4. Current Capability Matrix

| Format / workflow | Current Talos behavior | Evidence | Verdict |
|---|---|---|---|
| Markdown/plain text/source/config | Existing text read/search/edit flow | Full `clean check e2eTest` | Works for developer/text beta |
| PDF `.pdf` | Extracts text locally through PDFBox; warns about visual order/layout limits; no-text/scanned-style PDFs report `OCR_REQUIRED`; encrypted PDFs report `ENCRYPTED` | `DocumentExtractionAdaptersTest`, `ReadFileToolTest`, `GrepToolTest`, live prompt `05-pdf-summary` | Implemented for text PDFs |
| Word `.docx` | Extracts text locally through POI XWPF; layout/comments/tracked changes/embedded objects remain limited | `DocumentExtractionAdaptersTest`, live prompt `06-docx-summary` | Implemented for DOCX text |
| Legacy Word `.doc` | Deferred unsupported | `FileCapabilityPolicy` family `WORD_DOC_DEFERRED` | Not beta-ready |
| Excel `.xls`, `.xlsx` | Extracts visible cell text with sheet names/cell coordinates; formula cells show formula plus cached display value when available; skips hidden sheets with a warning; large extraction output is `PARTIAL`/truncated; corrupt workbooks report `CORRUPT`; no formula recalculation | `DocumentExtractionAdaptersTest`, `DocumentExtractionCanonicalFixturesTest`, live prompts `07-xlsx-summary`, `10-compare-xlsx-text` | Implemented for visible cell text |
| Images `.png/.jpg/.jpeg/.gif/.bmp/.webp/.tif/.tiff` | Frozen out of beta; experimental OCR adapter exists but is not beta evidence | `DocumentExtractionAdaptersTest`, `DocumentExtractionPreflightTest`; beta-core live audit excludes image prompts | v1/open issue |
| PowerPoint `.ppt/.pptx` | Frozen out of beta; truthful refusal expected | unsupported/frozen-format tests; beta-core live audit excludes PPT prompts | v1/open issue |
| Archives | Not recursed/extracted | capability policy and unsupported tests | Unsupported |
| Executables/binaries | Not inspected as documents | capability policy and unsupported tests | Unsupported |
| RAG indexing | Extractable text can be indexed when policy allows; protected/deferred/unsupported paths remain guarded | `IndexerPolicyMetadataTest`, `RagDirtyIndexIntegrationTest`, live prompt `11-reindex` | Better, still needs larger corpus |
| Private mode | Protects approved protected reads and private-mode extracted document text as local-display-only by default; `/show` skips stale index snippets when private-mode RAG is disabled | `ProtectedReadScopeIntegrationTest`, README, live private search prompt, live private PDF/DOCX/XLSX provenance prompts, private-folder bank | Useful, not enough for private-paperwork release |

## 5. Runtime Boundary State

| Boundary | Current state | Remaining risk |
|---|---|---|
| Model context | Indirect reads are sanitized/omitted. Private-mode extracted document text is withheld from model context by default in the focused generated-fixture audit. Enabled document extraction text can enter model context in developer/default mode when the target is not protected and the task requires synthesis. | Developer/default approved direct protected reads may still enter model context after approval. This is documented and remains a private-document risk outside private mode. |
| Prompt-debug/provider body | Targeted artifact scan passed for the latest live audit, including generated private-document fixture prompts. | The scan is only as good as the generated surfaces included in the run. Broader private-paperwork audit still needed. |
| Trace/session/turn logs | Central redaction and targeted scan passed for latest audit. | Need larger corpus and log-site review as code grows. |
| RAG index | Metadata includes privacy/file-capability/document-extraction policy; stale metadata rebuilds/refuses. | Real-world extraction cache/versioning and large corpus performance still need work. |
| Final answer truthfulness | Runtime shaping blocks unsupported/deferred overclaims and forces evidence reads for named extractable targets. | Model quality still varies; final answer quality must be judged against traces, not prose. |

## 6. Bugs Found During This Audit Cycle

| Finding | Impact | Fix |
|---|---|---|
| No-text/scanned-style PDFs were treated as successful empty extraction. | Could let Talos imply a PDF was reviewed when no text was extracted. | PDF adapter now returns `OCR_REQUIRED`; `read_file` fails honestly and grep reports skipped `OCR_REQUIRED` PDFs. |
| XLS/XLSX extraction included hidden sheets despite the visible-cell claim. | Hidden sheet data could enter model context while docs claimed visible-cell extraction. | Workbook extraction now skips hidden/very-hidden sheets and emits an `excel-hidden-sheets` warning. |
| Encrypted/corrupt documents collapsed into generic `FAILED`. | Generic failure is less auditable and makes final-answer limitations harder to enforce. | Extraction failure classification now returns `ENCRYPTED` for encrypted PDFs and `CORRUPT` for invalid/corrupt workbooks, with no model handoff. |
| Explicit config deny rules were evaluated after protected-read approval prompts. | The live audit could not force protected direct reads to fail closed; unexpected approval prompts consumed later trace/debug slash commands in piped stdin. | `DeclarativePermissionPolicy` now lets explicit `deny` rules beat protected-read `ask`; the live audit isolated config denies protected direct reads so trace/debug capture remains deterministic. |
| Image prompts did not always create named image read targets. | Model could answer image questions without reading `image.png`. | `TaskContractResolver` target regex now includes image/archive/binary extensions; evidence policy became config-aware. |
| Unsupported image fabrication scrubber missed verbs such as "shows" and "includes". | A bad model answer could claim visual content from unsupported images. | `AssistantTurnExecutor.isUnsupportedDocumentContentClaim` now catches more unsupported-content verbs. |
| GPT-OSS compare prompt read only `report.txt`, not `workbook.xlsx`. | Runtime truthfully blocked full comparison but functionality was incomplete. | Added partial multi-target evidence recovery for ordinary missing read targets. |
| Evidence recovery reopened protected/escaped/failure-policy paths. | Could violate existing protected-path/failure-boundary semantics. | Recovery now only runs for ordinary `READ_TARGET_REQUIRED`, skips denied outcomes, and skips failure-policy stops. |
| PDF extraction provenance hardcoded PDFBox 3.0.6 after dependency bump. | Stale runtime evidence. | Provenance now reads loaded package implementation version; test asserts it is not stale. |
| POI/PDF extraction emitted Log4j provider errors to CLI output. | User-visible noise during document reads. | Added `org.apache.logging.log4j:log4j-to-slf4j:2.25.4` runtime bridge. |
| Private-mode `/show` could use stale Lucene snippets after a developer-mode reindex. | A private-folder local-display check could bypass the explicit document extraction display path and omit the model-context marker. | `ShowCommand` now skips index snippet lookup in private mode unless private-mode RAG is explicitly enabled; regression test added. |

## 7. Strengths Worth Preserving

- Central runtime policy is doing the right work. Extraction is not bolted into one tool only; it is routed through read, grep, slash grep, and RAG.
- The extraction result type is better than raw strings. It carries status, warnings, provenance, policy version, safe text, and model-handoff intent.
- Evidence gates are now more honest. The model is not trusted to "remember" to read documents; the runtime forces named-target reads in key flows.
- The two-model audit script is useful. It creates fresh workspaces, captures prompt-debug/provider bodies, and runs both GPT-OSS and Qwen.
- Artifact scanning is now a repeatable command, not only a report claim.
- PowerPoint remains deferred instead of half-implemented. That is the correct beta discipline.

## 8. Weak Points And Pain Points

- `AssistantTurnExecutor` is too large and now carries too many postcondition/recovery responsibilities. The fixes are justified, but this class should eventually lose policy-heavy logic to smaller collaborators.
- The live audit uses generated fixtures. Checked-in canonical fixtures now prove independent small parser smoke coverage, but they still do not prove adversarial or real-world document quality.
- Image support is not beta scope. The code path and preflight exist, but images are frozen for v1 and must not be used as beta readiness evidence.
- PDF/DOCX/XLS/XLSX extraction is text-oriented. It does not prove layout fidelity, comments/tracked changes completeness, charts, embedded objects, or scanned PDF OCR. Formula cells now expose formula text plus cached display value where available, but Talos still does not recalculate formulas. Hidden Excel sheets are skipped and reported, not extracted silently. Encrypted, corrupt, and truncated documents fail or degrade with explicit statuses instead of generic review claims.
- RAG extraction is better but not yet performance-proven on large document folders.
- Some historical reports are now superseded and contain stale "not extractable" language. Current release decisions should use this report plus the latest live audit, not older dated sections.
- Git line-ending warnings are present on many touched files. They did not fail tests, but the repo should standardize `.gitattributes` before broad churn grows.

## 9. Evidence Commands Run

Deterministic tests:

```powershell
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest.xlsx_large_output_reports_partial_with_truncation_warning" --no-daemon
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionCanonicalFixturesTest" --no-daemon
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --tests "dev.talos.core.extract.DocumentExtractionCanonicalFixturesTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --tests "dev.talos.tools.impl.GrepToolTest" --tests "dev.talos.core.rag.RagDirtyIndexIntegrationTest" --tests "dev.talos.core.index.IndexerPolicyMetadataTest" --no-daemon
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon
./gradlew.bat clean check e2eTest --no-daemon
```

Results: passed.

10-domain stretch audit:

```powershell
./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommand*" --no-daemon
./gradlew.bat test --tests "*ProtectedPath*" --tests "*GrepTool*" --tests "*RetrieveTool*" --tests "*ArtifactCanary*" --tests "*SensitiveLog*" --no-daemon
./gradlew.bat test --tests "*DocumentExtraction*" --tests "*FileCapabilityPolicyV3*" --no-daemon
./gradlew.bat test --tests "*ReadFileTool*" --tests "*WorkspaceCommands*" --tests "*GrepTool*" --no-daemon
./gradlew.bat test --tests "*UnsupportedFinalAnswer*" --tests "*EvidenceObligation*" --tests "*TaskContractResolver*" --no-daemon
./gradlew.bat test --tests "*Rag*Dirty*" --tests "*RagDefaultConfigPrivacy*" --tests "*ConfigPrivacyDefaults*" --tests "*IndexerPolicyMetadata*" --no-daemon
./gradlew.bat test --tests "*SensitiveWorkspaceDetector*" --tests "*PromptDebug*" --tests "*JsonTurnLogAppender*" --tests "*LocalTurnTrace*" --no-daemon
./gradlew.bat test --tests "*RunCommandTool*" --tests "*Command*Policy*" --tests "*WorkspaceOperation*" --tests "*WorkspaceBatch*" --tests "*BatchWorkspaceApplyTool*" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "*ToolCallLoop*" --tests "*ToolCallParser*" --no-daemon
```

Results: passed. Consolidated local report:
`local/manual-testing/talos-stretch-audits-20260516-191848/TEN-STRETCH-AUDITS-RESULTS.md`.

Distribution:

```powershell
./gradlew.bat installDist --no-daemon
```

Result: passed.

Two-model live audit:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -BetaCoreOnly -PrivateFolderBank -StopStaleServers
```

Results: beta-core audit passed. Images and PowerPoint were intentionally excluded.

Latest live audit:

- `local/manual-testing/capability-live-audit-20260518-004603/LIVE-CAPABILITY-AUDIT-RESULTS.md`
- `local/manual-testing/capability-live-audit-20260518-004603/LIVE-CAPABILITY-AUDIT-SUMMARY.csv`
- `local/manual-testing/capability-live-audit-20260518-004603/PRIVATE-FOLDER-MANUAL-AUDIT-RUNBOOK.md`
- GPT-OSS prompts: 22/22 exit 0, no raw secret/canary leak detected by script, no unsupported overclaim detected.
- Qwen prompts: 22/22 exit 0, no raw secret/canary leak detected by script, no unsupported overclaim detected.
- Private-mode generated PDF/DOCX/XLSX fixture prompts: both models read the target files and answered with withheld-content wording instead of revealing the ordinary private fact fixture.
- Private-folder bank prompts: `/show` local-display, private-mode reindex disabled, private-mode retrieve-style behavior, and protected-read denial probes passed expected-output checks.
- Format scope: beta core; image/PPT prompts excluded.

Targeted artifact scan:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/capability-live-audit-20260518-004603,local/manual-workspaces/capability-live-audit-20260518-004603" "-PartifactScanAllowlist=<fixture allowlist>" --no-daemon
```

Result: passed.

Manual checks:

```powershell
rg "Log4j API could not find|ERROR Log4j|3\.0\.6" local/manual-testing/capability-live-audit-20260518-004603 -n
Get-Command tesseract -ErrorAction SilentlyContinue
```

Results: no stale Log4j/PDFBox evidence in latest audit. Tesseract is not beta-relevant while images are frozen.

## 10. Release Claims

Allowed for a developer/text-project beta candidate, if deterministic and live audit evidence is included and maintainer trace review is completed:

- Talos is a local developer workspace assistant.
- Talos can work with code, text, config, CSV/TSV, and static web folders.
- Talos can extract text from text PDFs with layout/order limitations; no-text/scanned-style PDFs require OCR and encrypted PDFs are not treated as reviewed.
- Talos can extract text from DOCX with structure/layout limitations.
- Talos can extract visible cells from XLS/XLSX without formula recalculation; formula cells expose formula text plus cached display value when available; hidden sheets are skipped with a warning; large output can be partial/truncated; corrupt workbooks fail explicitly.
- Talos identifies deferred/unsupported formats honestly.
- In private mode, generated-fixture PDF/DOCX/XLSX extracted document text is withheld from model context by default in the focused two-model audit.

Forbidden:

- Safe for tax folders.
- Safe for health records.
- Safe for legal paperwork.
- Safe for family/admin document folders.
- General private-document assistant.
- General PDF reviewer.
- General Word reviewer.
- General Excel analyst.
- Image OCR, image understanding, or visual analysis.
- PowerPoint reader.
- Global guarantee that protected content never reaches model context.

## 11. Ticket State

| Ticket | Current interpretation |
|---|---|
| T290 | Architecture spine implemented enough for beta text extraction, but still needs extraction-cache/performance hardening. |
| T291 | PDF text extraction implemented and live-audited for small text PDFs. No-text/scanned-style PDFs now report `OCR_REQUIRED`; encrypted PDFs report `ENCRYPTED`; OCR extraction remains v1/future work. |
| T292 | DOCX text extraction implemented and live-audited. Legacy `.doc` remains deferred. |
| T293 | XLS/XLSX visible-cell extraction implemented and live-audited. Hidden sheets are skipped with a warning, corrupt workbook fixtures report `CORRUPT`, formula cells show formula plus cached display value, and large output reports `PARTIAL`/truncated. Charts, macros, password protection, and real-world large workbook performance remain open. |
| T294 | OCR adapter and preflight implemented, but image/OCR is frozen out of beta and remains v1/open. |
| T295 | Extraction privacy boundary improved and artifact scan passed for latest audit, including generated private-document fact fixtures. Needs larger private corpus and explicit send-to-model UX evidence. |
| T296 | Extraction-aware RAG path implemented and tested; still needs performance/corpus evidence. |
| T299 | Live audit now runs with generated valid fixtures plus generated private-document ordinary-fact fixtures and a private-folder bank. Checked-in canonical PDF/DOCX/XLSX fixtures with expected-text files now exist. Still needs larger real-world and protected document fixture sets. |
| T301 | README updated; older reports are superseded by this report. Capability docs still need generated/drift-resistant tests. |
| T302 | PowerPoint correctly deferred. |
| T303 | Capability state machine implemented enough for current formats; dynamic outcomes still need more edge states. |
| T304 | Extraction policy version participates in index metadata; full extraction cache remains future work. |

## 12. Best Next Move

Do not start PowerPoint next. PPT can wait.

The next serious beta move is broader document and privacy evidence, not image/PPT:

1. Add real-world and adversarial document fixtures: messy PDFs, DOCX comments/tracked changes, password-protected workbooks, charts/macros, and large workbook performance cases.
2. Add larger protected/private document fixtures and artifact scans that prove extracted PDF/DOCX/XLS/XLSX text obeys private-mode/model-context boundaries beyond small generated fixtures.
3. Add scanned PDF routing evidence: text PDF uses PDFBox; scanned PDF must say OCR required because images/OCR are v1.
4. Split evidence recovery and unsupported-answer correction out of `AssistantTurnExecutor`.
5. Add explicit per-turn extracted-document send-to-model approval UX/tracing, separate from config-only opt-in.
6. Keep images and PowerPoint out of beta claims until the v1 tickets are implemented and audited.

Parallel but lower-risk work:

- Add `.gitattributes` to stop line-ending churn.


