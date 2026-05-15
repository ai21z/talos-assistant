# T267 and File-Format Release Gate Report

## 1. Executive verdict

Release-ready only for developer/text-project beta.

Talos is not release-ready for broader private-document beta. It still must not be positioned as safe for tax folders, health documents, legal paperwork, family/admin folders, or arbitrary private document folders because private-folder mode and local document extraction are not implemented.

## 2. Source crosscheck summary

OpenAI Codex comparison: sandboxing and approvals are separate. Approval reduces interruptions but does not replace enforced filesystem/content boundaries.

Gemini CLI comparison: tool calls are validated/executed by the runtime, and tool outputs are sent back to the model. That makes tool-result sanitization before model handoff mandatory.

Claude Code/leaked-source lessons: use only design lessons. Agent harnesses need explicit validation, permission checks, bounded loops, and artifact caution. No leaked implementation was copied.

Agent-design lesson: traces and prompt-debug artifacts are useful for audit, but they are also durable sensitive artifacts.

Talos implication: runtime policy must own protected content, unsupported-format truthfulness, and artifact redaction. Prompt-only fixes are unacceptable.

## 3. T267 status

Status: fixed for the tested indirect-read and artifact-safety paths required for a developer/text-project beta boundary.

Code changes:

- Added central protected-content redaction/sanitization policy.
- Sanitized tool results before they are appended to model-loop messages.
- Routed native `talos.grep`, slash `/grep`, `talos.retrieve`, RAG indexing/retrieval, prompt-debug, trace redaction, and session persistence through shared policy.
- Expanded protected path classification for `protected/`, `*.env`, password-like paths, and related secret path terms.
- Removed `.env` from default RAG includes and added protected default excludes.

Tests:

- Focused privacy, retrieval, artifact, protected-path, default-config, and unsupported-format tests pass.
- Full `clean check e2eTest` passes.
- Runtime artifact canary scan over generated build/local artifacts found no raw T267 canary values outside source/spec locations.

Remaining risks:

- Dirty historical RAG indexes may still exist on user machines. Retrieval-time sanitization now reduces leak risk, but index invalidation/versioning remains an open hardening item.
- Private-folder mode is still open.

## 4. Unsupported-format status

| Format family | Extensions | Current behavior | Tests | Verdict |
|---|---|---|---|---|
| PDF | `.pdf` | Classified unsupported; direct read/write should be honest; RAG excludes. | Existing and focused unsupported tests | Not extractable |
| Word | `.doc`, `.docx` | Classified unsupported; grep/search skips and reports. | Focused grep/format tests | Not extractable |
| Excel | `.xls`, `.xlsx` | Classified unsupported; direct read/write should be honest. | Focused unsupported tests | Not extractable |
| PowerPoint | `.ppt`, `.pptx` | Classified unsupported. | Focused unsupported tests | Not extractable |
| Images/scans | `.png`, `.jpg`, `.jpeg`, `.gif`, `.bmp`, `.webp`, `.tif`, `.tiff` | Classified unsupported image/scan. No OCR. | Focused unsupported tests | Not extractable |
| Archives | `.zip`, `.tar`, `.gz`, `.tgz`, `.7z`, `.rar` | Classified unsupported archive. | Focused unsupported tests | Not extractable |
| Binaries | `.exe`, `.dll`, `.so`, `.dylib`, `.class`, `.jar`, `.war`, `.ear`, `.bin`, `.dat` | Classified unsupported binary/compiled. | Focused unsupported tests | Not extractable |
| Unknown text-like files | no known unsupported extension, no binary sniff failure | Text attempt allowed. | Existing parser/read/search tests | Supported cautiously |

## 5. Artifact safety status

| Surface | Can raw protected/canary content appear? | Evidence | Verdict |
|---|---|---|---|
| model context | Should not for tested indirect tool-result paths | `ToolCallExecutionStage` sanitizes indirect `ToolResult` values before message append; focused formatting test passes | Pass for tested boundary |
| provider body | Should not for tested grep/tool-result captures | prompt-debug provider-body test passes | Pass for tested boundary |
| prompt-debug markdown | Should not for tested grep/tool-result captures | prompt-debug render test passes | Pass for tested boundary |
| prompt-debug provider-body JSON | Should not for tested grep/tool-result captures | prompt-debug save test passes | Pass for tested boundary |
| local turn trace | Redaction policy now covers canaries/private markers | focused redactor test passes | Pass for tested boundary |
| session JSON | Save path redacts JSON text-node values without corrupting JSON | session persistence regression tests pass | Pass for tested boundary |
| turn JSONL | Should not for tested grep-style turn record | session turn log test passes | Pass for tested boundary |
| logs | Runtime artifact scan found no raw T267 canaries in generated build/local artifacts | canary scan command passed with no hits | Pass for tested boundary |
| RAG index | Protected/unsupported files excluded by code and config; dirty retrieval sanitized/skipped | default config and retrieve dirty-index tests pass | Pass for tested boundary |
| final answer | Model should not receive raw indirect tool-result canaries in tested path | model-context/tool-result tests pass | Pass for tested boundary |

## 6. User-facing copy recommendation

Allowed claims:

- local developer workspace assistant
- good for code, text, config, CSV/TSV, and static web folders
- approved edits and evidence-oriented outcomes
- local-first execution harness
- unsupported documents are identified honestly rather than silently summarized

Forbidden claims unless all gates pass:

- safe for tax folders
- safe for health documents
- safe for legal paperwork
- safe for family/admin private paperwork
- can read PDFs
- can read Word documents
- can read Excel workbooks
- can read PowerPoint decks
- can summarize images/scans
- can inspect arbitrary binary files

## 7. Tickets created/updated

| Ticket | Severity | Release gate? | Next step |
|---|---:|---|---|
| T267 indirect-read tools must not leak protected content | P0 | yes | Keep dirty-index invalidation/versioning as follow-up hardening |
| T268 unsupported document formats must not be misrepresented | P0 | yes | Broaden final-answer unsupported-format scenarios |
| T269 user-facing file capability matrix and beta warning | high | yes | Keep README/docs in sync with runtime |
| T270 RAG index protected and unsupported format safety | high | yes | Add index version/invalidation decision |
| T271 prompt-debug/trace/session redaction release gate | high | yes | Expand artifact scan coverage beyond current T267 fixtures |
| T272 private folder mode | high | yes for sensitive-document beta | Design/implement before private paperwork positioning |
| T273 local document extraction roadmap | medium | no for developer beta | Plan local parsers/OCR |
| T274 source-crosscheck and release-gate discipline | high | yes | Keep source matrix required for future trust gates |

## 8. Tests run

- `./gradlew.bat test --tests "*GrepToolTest" --tests "*WorkspaceCommandsTest" --tests "*ProtectedPathPolicyTest" --tests "*TraceRedactorTest" --tests "*ToolCallSupportTest" --tests "*RetrieveToolTest" --tests "*UnsupportedDocumentFormatsTest" --tests "*RagDefaultConfigPrivacyTest" --tests "*PromptDebugCommandTest" --tests "*JsonSessionStoreTurnsTest" --no-daemon` - passed.
- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*approvedProtectedReadRefusalUsesRuntimePostcondition*" --tests "dev.talos.cli.repl.ExplainLastTurnCommandTest*traceViewIncludesLocalTraceWhenTurnHasTraceId*" --tests "dev.talos.runtime.JsonTurnLogAppenderTest*writesStructuredRecordWithProtectedContentRedacted*" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed.
- `rg -n --hidden -a --glob '!classes/**' --glob '!tmp/**' --glob '!generated/**' "DO_NOT_LEAK_T267|t267-token-should-not-appear|t267-password-should-not-appear|t267-client-secret-should-not-appear|TALOS_CANARY_T267|PRIVATE_MARKER = DO_NOT_LEAK_T267|API_TOKEN=t267-token" build local 2>$null` - no hits.

## 9. Tests not run

- No two-model live Talos audit was run in this work cycle.
- No manual prompt-bank audit was run in this work cycle.

## 10. Remaining blockers

- Not ready for sensitive personal paperwork positioning.
- Private-folder mode is not implemented.
- Local PDF/Office/image/OCR/archive extraction is not implemented.
- RAG index invalidation/versioning for old dirty indexes is not implemented.
