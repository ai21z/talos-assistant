# Prompt Debug Comparison And Document Capability Audit - 2026-05-20

## Environment

```text
Branch: v0.9.0-beta-dev
Base commit: 0967ba46c1daad7789e0bc5df1746e8cc4883e52
Candidate version: 0.9.9
Version bump: no
Audit type: redirected-stdin prompt-debug smoke plus static worker review
Backend/model: managed llama.cpp / gpt-oss-20b where live smoke was run
```

These audits are not true PTY/JLine approval evidence. They are suitable for prompt-debug, provider-body, no-workspace, document extraction, and command-boundary smoke invariants. Approval-sensitive tickets still require synchronized or manual terminal evidence.

## Audits Run

```text
prompt-debug-comparison-20260520-r1/general
prompt-debug-comparison-20260520-r1/documents
prompt-debug-comparison-20260520-r1/python-boundary
prompt-debug-no-workspace-fix-20260520-r1
prompt-debug-python-tool-surface-fix-20260520-r1
```

Each natural-language smoke turn used `/debug prompt on` and `/last trace`. Prompt-debug artifacts were saved where the invariant depended on prompt/provider-body construction.

## Finding 1 - No-Workspace Compound Phrase Gap

Severity: P0 before fix, because the invariant is privacy/minimization.

The prompt:

```text
Without inspecting or using this workspace, explain what entropy means in thermodynamics in two short paragraphs.
```

classified as workspace diagnostic at base commit `0967ba46`, exposed workspace tools, and called `talos.list_dir`.

Root cause:

```text
TaskContractResolver and ConversationBoundaryPolicy recognized simpler no-workspace phrasings but not compound "inspect or use workspace" phrasings.
```

Fix:

```text
Added explicit no-workspace markers for "without using this workspace" and "without inspecting or using this workspace" variants.
```

Post-fix evidence:

```text
Audit id: prompt-debug-no-workspace-fix-20260520-r1
Result: contract SMALL_TALK, nativeTools none, promptTools none, no tool calls.
```

## Finding 2 - Textual Tool Prompt Mismatched Native Tool Surface

Severity: High before fix. This was not native command exposure, but it was prompt-level dishonesty and model-confusion risk.

The Python-boundary audit showed:

```text
CurrentTurnCapability visibleTools: talos.read_file
provider-body tools array: talos.read_file only
textual system prompt: described talos.run_command as available
```

Root cause:

```text
UnifiedAssistantMode built the human-readable tool section from coarse read-only/verification flags before aligning it with NativeToolSpecPolicy's exact per-turn tool plan.
```

Fix:

```text
SystemPromptBuilder now accepts exact visible tool names and filters both tool descriptors and verification-command preamble text against that set.
UnifiedAssistantMode and PromptInspector pass the planned per-turn native tool names into the prompt builder.
```

Post-fix evidence:

```text
Audit id: prompt-debug-python-tool-surface-fix-20260520-r1
Transcript: local/manual-testing/prompt-debug-python-tool-surface-fix-20260520-r1/artifacts/TRANSCRIPT.txt
Provider-body scan: 0 occurrences of talos.run_command
Prompt audit: nativeTools talos.read_file; promptTools talos.read_file
```

## Finding 3 - PDF/DOCX/XLSX Extraction Works For Narrow Text Fixtures

The document audit copied checked-in canonical fixtures into a fresh audit workspace:

```text
canonical-text.pdf
canonical-report.docx
canonical-workbook.xlsx
```

Talos successfully used `talos.read_file` and surfaced the fixture markers:

```text
CANONICAL_PDF_TEXT_ALPHA
CANONICAL_DOCX_TEXT_BETA
CANONICAL_XLSX_TEXT_GAMMA
```

Interpretation:

```text
Talos can claim narrow local text extraction for text-bearing PDF, DOCX, XLS, and XLSX files.
Talos must not claim layout-perfect understanding, binary document generation, scanned-PDF OCR by default, formula recalculation, chart/macro support, or private paperwork readiness.
```

This supports current extraction capability claims. It does not close `T323`, because `T323` is about multi-source office-report verification, not merely reading individual document fixtures.

## Python Boundary Status

The Python-boundary audit remained honest:

```text
Talos did not claim pytest or Python execution.
Talos read problem.md when asked for evidence.
Talos stated that Python tests cannot be run in the current tool surface.
```

`T325` remains open only for synchronized/manual mini-audit evidence around the approval-sensitive `t325-python-command-boundary` case.

## Worker Review Summary

Read-only no-workspace review confirmed the expected invariant:

```text
No-workspace and small-talk turns should have SMALL_TALK contract, no workspace manifest, no README excerpt, no RAG snippets, no native tools, and no workspace canaries in provider body.
```

Read-only document-capability review confirmed current beta boundaries:

```text
Allowed: text extraction from text-bearing PDF/DOCX/XLS/XLSX through local extraction.
Deferred or unsupported: DOC legacy generation/editing, PDF generation, scanned PDF without OCR configuration, image/OCR product claims, PowerPoint, charts/macros/formula recalculation, private paperwork release claims.
```

## Verification Evidence

Focused commands run during this slice:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest.privacyNegatedChatPromptsSuppressWorkspaceInspectionIntent" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.explicitNoWorkspaceOrUsingWorkspacePromptDoesNotExposeTools" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.policy.ConversationBoundaryPolicyTest" --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.pythonReadOnlyTargetPromptDoesNotDescribeHiddenCommandTool" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptInspectorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon
.\gradlew.bat installDist --no-daemon
```

One parallel Gradle attempt failed because another test process held `build/test-results/test/binary/output.bin`. The affected suite was rerun serially and passed. Do not run parallel Gradle `test` invocations in the same checkout on Windows for this repo.

## Remaining Blockers

```text
T307 - semantic verification beyond exact edits
T322 - exact three-file static web convergence
T323 - office document multi-source report verification
T325 - synchronized/manual mini-audit for Python command-boundary approval-sensitive case
T299/T300/T301/T320 - document fixture, performance, docs, and capability-claim hardening
```

## Next Best Move

The next implementation move should remain `T307` or the focused live evidence for `T325`, depending on whether the next slice is code or audit. Do not start PDF/Office expansion. The document work should harden claims, fixtures, and multi-source verification before adding formats or generation.
