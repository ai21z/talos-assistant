# T267 Source Crosscheck

## 1. Scope

This crosscheck covers the T267 release gate: indirect-read privacy, unsupported or weakly supported file-format truthfulness, artifact redaction, provider-body/model-context safety, and documentation/ticket discipline for beta positioning.

Branch under audit: `v0.9.0-beta-dev`.

External network access was available. Primary/reputable sources inspected:

- Talos local branch: `C:\Users\arisz\Projects\LOQ\loqj-cli`
- OpenAI Codex docs/source:
  - https://developers.openai.com/codex/agent-approvals-security
  - https://developers.openai.com/codex/concepts/sandboxing
  - https://developers.openai.com/codex/guides/agents-md
  - https://developers.openai.com/codex/config-reference
  - https://github.com/openai/codex/blob/main/codex-rs/core/config.schema.json
  - https://openai.com/index/running-codex-safely/
  - https://openai.com/index/unrolling-the-codex-agent-loop/
- Gemini CLI docs/source:
  - https://google-gemini.github.io/gemini-cli/docs/tools/
  - https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/tools.md
  - https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/policy-engine.md
  - https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/sandbox.md
- Required comparative repositories:
  - https://github.com/chauncygu/collection-claude-code-source-code/tree/main/claude-code-source-code
  - https://github.com/ultraworkers/claw-code
  - https://github.com/yasasbanukaofficial/claude-code
  - https://github.com/google-gemini/gemini-cli
  - https://github.com/openai/codex

Project-provided secondary source `alex000kim-article.txt` was searched with recursive filesystem lookup and was not found in this workspace. This report does not rely on it.

## 2. Talos current evidence

### Direct protected read behavior

Evidence:

- `src/main/java/dev/talos/runtime/policy/ProtectedPathPolicy.java`
  - `ProtectedPathPolicy.classify(Path, ToolCall)` and `classify(Path, String)` classify path arguments.
  - `protectedKind(String)` protects `.env`, `.env.*`, `secrets`, `.ssh`, `.aws`, `.azure`, `.config/gcloud`, private-key filenames, private-key extensions, and filenames containing `secret`, `token`, or `credential`.
  - It does not currently protect a directory literally named `protected/`, `.env` without an extension when matched through RAG config, or filename terms such as `password` and `private`.
- `src/main/java/dev/talos/runtime/policy/DeclarativePermissionPolicy.java`
  - `decide(PermissionRequest)` calls `ProtectedPathPolicy.classifyAll(...)`.
  - If a protected resource is used with a mutating tool, it denies mutation.
  - If a protected resource is used with a non-mutating direct read tool, it requires approval.
  - `isSpecificReadTool(String)` recognizes only direct read-file names: `talos.read_file`, `read_file`, `readfile`.
- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
  - Approval UI supports approve/deny/remember behavior.

Conclusion: direct `talos.read_file(".env")` has a runtime gate. The gate is path-argument based and does not automatically cover indirect tools that discover protected files internally.

### Native `talos.grep` behavior

Evidence:

- `src/main/java/dev/talos/tools/impl/GrepTool.java`
  - Tool descriptor marks `talos.grep` as `ToolRiskLevel.READ_ONLY`.
  - `SKIP_DIRS` only skips VCS/build/cache/tool directories: `.git`, `.svn`, `.hg`, `node_modules`, `__pycache__`, `.gradle`, `build`, `.idea`, `.talos`, `.loqj`.
  - `execute(...)` walks the workspace with `Files.walkFileTree(...)`.
  - It checks `ctx.sandbox().allowedPath(file)` but does not call `ProtectedPathPolicy` for each visited file.
  - Unsupported document skipping only happens inside the include-glob branch. Without an include glob, unsupported document classification is not applied before binary sniffing.
  - `searchFile(...)` reads all lines and appends `relPath:line | raw line` to tool output.

Live audit evidence:

- `local/manual-testing/codex-talos-audit-20260515-070016/FINDINGS.md`
  - `T267-LIVE-001` records that Prompt 17 caused `talos.grep` to return raw marker lines from `notes.md` and `protected/private-notes.md`.
  - Qwen repeated the marker values in the final answer.
  - GPT-OSS avoided final-answer repetition, but provider-body and prompt-debug artifacts still contained the raw values.

Conclusion: native grep is currently an indirect-read privacy bypass.

### Slash `/grep` behavior

Evidence:

- `src/main/java/dev/talos/cli/repl/slash/GrepCommand.java`
  - Implements a separate grep path, not a wrapper around `GrepTool`.
  - It builds its own file matchers for code, docs, and config files.
  - It includes `.env`-extension files through `*.env`.
  - It skips only `build/`, `target/`, `.git/`, and `.idea/`.
  - It reads each selected file with `Files.readString(file)`.
  - It prints raw matching lines with optional 120-character truncation.
  - It does not call `ProtectedPathPolicy`, `UnsupportedDocumentFormats`, or any shared redaction policy.

Conclusion: slash `/grep` is a separate unsafe backdoor unless routed through the same content policy as native `talos.grep`.

### Retrieve/RAG behavior

Evidence:

- `src/main/java/dev/talos/tools/impl/RetrieveTool.java`
  - `doRetrieve(...)` calls `ragService.prepare(...)`.
  - It prints each snippet text with `truncate(snippet.text(), 1000)`.
  - It does not sanitize snippets before returning the tool result.
- `src/main/java/dev/talos/core/rag/RagService.java`
  - `prepare(...)` ensures an index exists.
  - It reads stored snippet text with `store.getTextByPath(c.path())`.
  - It constructs `ContextResult.Snippet(c.path(), text, c.metadata())` with the stored raw text.
- `src/main/java/dev/talos/core/index/Indexer.java`
  - `index(...)` builds include/exclude globs from RAG config.
  - `createFileFilter(...)` uses only configured globs.
  - During indexing, `ParserUtil.smartParse(p)` returns text which is chunked and stored.
  - It does not apply protected path exclusion independently of config.
- `src/main/resources/config/default-config.yaml`
  - RAG includes `**/*.env`.
  - Excludes include `.git`, IDE/build folders, archives/images/PDF/executables, but do not exclude `.env`, `.env.*`, `secrets/**`, `.ssh/**`, `.aws/**`, `.azure/**`, `.gnupg/**`, `.config/gcloud/**`, or `protected/**`.

Conclusion: RAG can index and later retrieve protected or secret-like text. Retrieval-time sanitization is also required because dirty old indexes may already contain raw content.

### Unsupported-format behavior

Evidence:

- `src/main/java/dev/talos/core/ingest/UnsupportedDocumentFormats.java`
  - Covers only `.pdf`, `.doc`, `.docx`, `.xls`, `.xlsx`, `.ppt`, `.pptx`.
  - `capabilityMessage(...)` truthfully says Talos cannot extract the document contents with the current local text-tool surface.
  - `writeCapabilityMessage(...)` truthfully says Talos cannot create valid binary Office/PDF files with the text-file surface.
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
  - Calls `UnsupportedDocumentFormats.isUnsupported(resolved)` before normal text read.
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
  - Blocks writes to unsupported document formats.
- `src/main/java/dev/talos/core/ingest/ParserUtil.java`
  - Calls `UnsupportedDocumentFormats.isUnsupported(file)` before reading text.
  - Uses a null-byte sniff to reject some binaries.
  - Does not classify images, scans, archives beyond configured RAG excludes, compiled binaries, `.jar`, `.class`, or generic binary types through a central capability policy.
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
  - Reports unsupported PDF/Office documents only in include-glob paths.
- `src/main/java/dev/talos/cli/repl/slash/GrepCommand.java`
  - Does not use unsupported-format classification.

Conclusion: unsupported-format truthfulness exists for direct read/write of PDF/Office formats, but it is partial and not centralized. Images, scans, archives, compiled files, generic binaries, slash grep, and RAG behavior remain unclear or weak.

### Prompt-debug/provider-body/trace/session behavior

Evidence:

- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
  - `execute(...)` calls `turnProcessor.executeTool(...)`.
  - Raw successful read-only outputs are saved in `state.successfulReadCalls` and `state.successfulReadCallBodies`.
  - It formats the result with `ToolCallSupport.formatToolResult(...)`.
  - It appends the formatted result to model-loop messages via `appendResultMessage(...)`.
  - Therefore, raw tool output can enter model context before prompt-debug or final-answer redaction runs.
- `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java`
  - `formatToolResult(...)` inserts raw `result.output()` into `[tool_result: ...]`.
  - It only truncates long output at 32K chars.
  - It does not sanitize protected content or unsupported-format claims.
- `src/main/java/dev/talos/runtime/trace/TraceRedactor.java`
  - Redacts secret-like assignments when keys contain secret/token/api-key/password/credential/private-key terms.
  - Does not redact `DO_NOT_LEAK*`, `TALOS_CANARY_*`, `CANARY_*`, or `PRIVATE_MARKER = ...`.
  - `looksSensitivePath(...)` covers `.env`, `/secrets/`, secret/token/credential/id_rsa/id_ed25519/private-key patterns, but not `protected/`.
- `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java`
  - `PROTECTED_CONTENT_SIGNAL` only detects keys like api-key/token/secret/password/credential/bearer with assignment syntax.
  - Provider body redaction delegates to `TraceRedactor.redactSecretLikeAssignments(...)`.
  - It does not centrally redact canaries/private markers.
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
  - `save(...)` writes turn role/content to session JSON.
  - `appendTurn(...)` writes user input, assistant text, tool trace summary, policy trace, and other turn fields to JSONL.
  - `saveTrace(...)` writes `LocalTurnTrace` as pretty JSON.
  - Redaction is not owned by the store itself; it depends on upstream objects already being safe.
- `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java`
  - Persists local traces and structured turn records after turns.
  - Its summary helper can serialize trace entries; it does not itself own a complete canary/protected-content policy.

Conclusion: artifact redaction is fragmented and misses canaries. The critical boundary is before tool results are appended back into model-loop messages.

### RAG include/exclude defaults

Evidence:

- `src/main/resources/config/default-config.yaml`
  - Includes text/code/config files and also includes `**/*.env`.
  - Excludes selected build folders, archives/images/PDF/executables.
  - Missing protected excludes: `**/.env`, `**/.env.*`, `**/*.env`, `**/secrets/**`, `**/.ssh/**`, `**/.aws/**`, `**/.azure/**`, `**/.gnupg/**`, `**/.config/gcloud/**`, `**/protected/**`.
  - Missing unsupported excludes for Office formats, PowerPoint formats, many image formats, archive variants, compiled artifacts, and generic binary extensions.

Conclusion: default config currently contradicts private-document readiness.

## 3. OpenAI Codex comparison

### Sandbox modes / permission profile

OpenAI Codex docs separate sandboxing from approval policy. The sandbox is the technical boundary; approval decides when Codex must stop before crossing it. The agent approvals/security page states that local Codex uses OS-enforced sandboxing with default no-network and workspace-limited writes, and that read-only mode is available for planning/browsing. The sandboxing page further states that spawned commands inherit the same sandbox boundaries.

The configuration reference exposes named filesystem permission profiles, including project-root glob rules such as `**/*.env = "none"` to deny reads.

Applicable Talos lesson: Talos needs a runtime-enforced permission/content boundary, not prompt language. If a path/content class is sensitive, enforcement must happen before tool output reaches the model.

Not directly applicable: Codex's cloud container setup, enterprise managed requirements, and OS sandbox internals do not map one-for-one to Talos's current Java runtime.

### Approval policies

OpenAI Codex supports approval modes including `on-request`, `never`, granular approval policy, and dangerous full-access/no-approval combinations. The Codex config schema describes approval policy as controlling when the user is consulted before commands run. Codex docs also describe that disabling approval prompts still leaves the chosen sandbox mode as a separate constraint.

Applicable Talos lesson: approval is not the boundary. Approval is a decision layer on top of technical constraints. Talos should not allow model-visible raw protected content just because a read-only tool did not require approval.

Not directly applicable: Codex auto-review is a second-agent review system. Talos's standard explicitly rejects solving T267 by adding more agent theater.

### Approval reviewer / escalation model

OpenAI Codex docs describe `approvals_reviewer = "user"` by default and optional `auto_review`. The reviewer only evaluates actions that already require approval and fails closed on prompt-build, review-session, and parse failures.

Applicable Talos lesson: any future Talos reviewer or policy assistant must sit after runtime classification and must fail closed. It cannot replace `ProtectedContentPolicy`.

### Command/tool policy

OpenAI docs describe protected paths in writable roots and filesystem deny-read profiles. The "Running Codex safely" article emphasizes clear technical boundaries, managed configuration, constrained execution, network policies, and logs for auditability.

Applicable Talos lesson: command and file operations need precise policy classes and audit artifacts. Talos should expose only the correct tool surface per phase and sanitize all tool outputs before model handoff.

### AGENTS.md/repo-instruction handling

OpenAI Codex docs say Codex reads `AGENTS.md` before work, merges global/project/current-directory instructions, and treats more specific instructions as later/higher precedence. These are prompt instructions, not technical security boundaries.

Applicable Talos lesson: Talos docs/AGENTS guidance can define audit standards, but privacy must live in runtime policy.

## 4. Gemini CLI comparison

### Sandbox documentation

Gemini CLI docs describe tool-level sandboxing for tool executions like shell and write-file, with sandbox expansion requests when extra permissions are needed. They also state the sandbox has access to the current workspace by default, with explicit mounts for external paths.

Applicable Talos lesson: workspace access and expansion should be explicit, visible, and per action. Talos should treat "workspace-local" as necessary but not sufficient for sensitive files.

Not directly applicable: Gemini's Docker/container/mount implementation is not Talos's runtime design.

### File-system isolation

Gemini docs describe confirmation for tools that modify files or run commands, and sandboxing for isolation. The tools documentation makes clear that tools access local files, execute commands, and return outputs to the model.

Applicable Talos lesson: because tool output is sent back to the model, sanitization must occur before that handoff.

### Policy engine / checker design

Gemini CLI's policy engine lets users/admins define allow/deny/ask decisions for tool calls. It has tiered precedence: admin overrides user, workspace, and default. Approval modes include `default`, `autoEdit`, `plan`, and `yolo`; plan is described as strict/read-only.

Applicable Talos lesson: Talos should keep policy decisions centralized and mode-aware. A read-only mode still needs privacy checks because read-only tools can leak.

Not directly applicable: Gemini's TOML policy language and mode names should not be copied directly.

### Command/shell safety

Gemini's tools reference says the CLI evaluates tool requests against security policies and shows diffs or exact commands for mutators. It also allows inspection of active tools with `/tools`.

Applicable Talos lesson: Talos should keep traceable tool visibility and should be able to explain which tools were visible and why.

## 5. Claude Code / leaked-source lessons

No code was imported or copied from leaked-source repositories.

Sources inspected:

- `chauncygu/collection-claude-code-source-code` README states the repository is extracted/unbundled code from an npm package and presents an architecture with entry layer, query engine, tool system, service layer, state layer, permission utilities, sandbox runtime adapter, bash helpers, messages, telemetry, and hooks.
- `ultraworkers/claw-code` README describes an independent Rust implementation/harness with usage, parity, and local-provider workflows.
- `yasasbanukaofficial/claude-code` README describes the leak mechanism through published source maps and presents high-level architecture only.

Design lessons only:

- Execution harness quality matters more than model prose.
- Tool systems need explicit validation, permission checks, rendering, and state tracking.
- Command safety needs specific checks, not broad "be careful" prompts.
- Failure loops need bounded retry/repair behavior.
- Debug, prompt, transcript, telemetry, and cache artifacts can become durable sensitive records.
- Source maps/prompt-debug/provider-body captures are themselves artifact surfaces and must be treated as leak targets.

Rejected for Talos:

- Copying leaked implementation.
- Importing multi-agent, remote-control, or telemetry-heavy architecture.
- Treating leaked-source behavior as a product standard.

## 6. Design conclusion for Talos

T267 must be fixed by a central runtime content policy plus targeted tool integrations.

Required:

- Central runtime content policy.
- Per-tool patches that delegate to that policy.
- Prompt/docs updates only as explanatory layer.

Unacceptable:

- Prompt-only changes.
- Final-answer-only redaction.
- Prompt-debug-only redaction.
- Config-only RAG exclusion.
- Fixing `talos.grep` while leaving `/grep`, `talos.retrieve`, RAG, provider-body, trace, session, and logs unsafe.

Expected central policy:

- `dev.talos.runtime.policy.ProtectedContentPolicy`
- It should own protected path classification delegation, protected content detection, canary/private-marker detection, secret-like assignment detection/redaction, search/retrieve output sanitization, prompt-debug/provider-body redaction helper, trace/session/log redaction helper, and generated-artifact canary scanning helpers.

Format truthfulness should use either:

- `dev.talos.core.ingest.FileCapabilityPolicy`, or
- `dev.talos.runtime.policy.FileFormatCapabilityPolicy`

It should classify searchable text, unsupported document, unsupported image/scan, unsupported archive, unsupported compiled/executable, unsupported binary, unknown text attempt allowed, and unknown binary skip.

## 7. Implementation plan

Exact files expected to change:

- Add `src/main/java/dev/talos/runtime/policy/ProtectedContentPolicy.java`
- Add or evolve `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- Update `src/main/java/dev/talos/runtime/policy/ProtectedPathPolicy.java`
- Update `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- Update `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java`
- Update `src/main/java/dev/talos/tools/impl/GrepTool.java`
- Update `src/main/java/dev/talos/cli/repl/slash/GrepCommand.java`
- Update `src/main/java/dev/talos/tools/impl/RetrieveTool.java`
- Update `src/main/java/dev/talos/core/rag/RagService.java`
- Update `src/main/java/dev/talos/core/index/Indexer.java`
- Update `src/main/java/dev/talos/core/ingest/UnsupportedDocumentFormats.java` or replace it via the new format policy
- Update `src/main/java/dev/talos/core/ingest/ParserUtil.java`
- Update `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- Update `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
- Update `src/main/java/dev/talos/runtime/trace/TraceRedactor.java`
- Update `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java`
- Update `src/main/java/dev/talos/runtime/JsonSessionStore.java` and/or callers that create persisted session/turn/trace data
- Update `src/main/resources/config/default-config.yaml`

Exact tests expected to add/update:

- `src/test/java/dev/talos/runtime/policy/ProtectedContentPolicyTest.java`
- `src/test/java/dev/talos/runtime/policy/ProtectedPathPolicyTest.java`
- `src/test/java/dev/talos/tools/impl/GrepToolTest.java`
- `src/test/java/dev/talos/cli/repl/slash/GrepCommandTest.java` or an existing slash-command test file
- `src/test/java/dev/talos/tools/impl/RetrieveToolTest.java`
- `src/test/java/dev/talos/core/rag/*Privacy*Test.java` or focused RAG safety tests
- `src/test/java/dev/talos/core/ingest/FileCapabilityPolicyTest.java`
- `src/test/java/dev/talos/runtime/trace/TraceRedactorTest.java`
- `src/test/java/dev/talos/cli/repl/slash/PromptDebugCommandTest.java`
- `src/test/java/dev/talos/runtime/JsonTurnLogAppenderTest.java`
- Optional e2e cases in `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

Documentation/tickets expected:

- `work-cycle-docs/reports/source-comparison-matrix.md`
- `work-cycle-docs/reports/t267-and-file-format-release-gate.md`
- T267-T274 tickets under `work-cycle-docs/tickets/open/`
- README/docs capability matrix and beta warning.

## 8. Risk register

- Dirty RAG indexes: even after default excludes, old indexes may contain raw protected snippets. Retrieval-time sanitization is mandatory.
- Artifact tests can leak canaries into build logs if assertions print raw values. Tests should use helper assertions that avoid dumping disallowed strings.
- Central redaction can over-redact legitimate code examples containing `token` or `secret`. This is acceptable for beta privacy, but user-facing notes should say values were redacted.
- Slash `/grep` is a separate code path. It must be fixed or removed/routed through shared grep implementation.
- `ProtectedPathPolicy` expansion to `protected/` and `password/private` terms can affect existing workflows. Tests must clarify intended behavior.
- Unsupported-format policy can accidentally block text-like files with unknown extensions. Use binary sniffing and clear categories rather than extension-only denial.
- RAG config changes can break existing `.env` indexing expectations. That is correct for privacy release gates but should be called out in release notes.
- Provider-body and prompt-debug redaction must happen before save/display; model-context safety must happen earlier, before message append.
- Full `./gradlew clean check e2eTest --no-daemon` may take minutes but is required before any release-gate claim.

## 9. 2026-05-15 hardening update

This report was re-checked against current source and official upstream docs during the next-release hardening pass.

Local source update:

- `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java` now separates approved protected reads into default/developer send-to-model behavior and private-mode `LOCAL_DISPLAY_ONLY` behavior.
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java` now withholds successful protected direct-read output from model-loop messages when policy does not allow `SEND_TO_MODEL_CONTEXT`.
- `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java` now provides a deterministic artifact canary scan path.
- `src/main/java/dev/talos/core/index/Indexer.java` now writes/checks privacy and file-capability policy metadata for RAG indexes.
- `src/main/java/dev/talos/core/rag/RagService.java` rebuilds stale/missing-policy indexes instead of silently trusting them.

Updated OpenAI Codex source/doc check:

- `https://developers.openai.com/codex/agent-approvals-security` states Codex uses a sandbox layer for what the agent can technically do and an approval policy layer for when it must ask before acting.
- `https://developers.openai.com/codex/concepts/sandboxing` lists read-only, workspace-write, and danger-full-access as separate sandbox modes, with approval policies such as on-request and never.
- `https://github.com/openai/codex/blob/main/codex-rs/core/config.schema.json` still exposes `approval_policy` and `approvals_reviewer` as config concepts.

Updated Gemini CLI source/doc check:

- `https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/sandbox.md` describes sandbox configuration, current-workspace mounting, sandbox expansion, and explicit outside-workspace mounts.
- `https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/policy-engine.md` documents allow/deny/ask_user policy decisions and mode-aware approval behavior.
- `https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/tools.md` documents that tools extend the model by reading files, executing commands, and searching, with confirmation for mutating tools and commands.

`alex000kim-article.txt` status:

- Searched locally for `alex000kim-article.txt`, `Claude Code Source Leak`, `KAIROS`, `bashSecurity`, and `promptCacheBreakDetection`.
- The article is still absent from this repository workspace.
- This report does not claim to have used that article.

Current conclusion:

Central runtime policy remains required. The new scope control, parameter/log sanitization, artifact scanner, and RAG policy metadata move Talos closer to a developer/text-project beta boundary, but they do not complete private-document release readiness. Approval is now explicitly documented as separate from privacy safety.
