# T820 ContextItem Tool-Result Adapter Cycle Break

Status: done
Priority: high
Wave: 5
Owner: architecture/core-tools package boundary

## Summary

Remove the `core.context.ContextItem` dependency on concrete `tools` types as
the first production step in the `core -> tools` cycle-break arc.

T820 is behavior-preserving architecture work. It should reduce the generated
`core -> tools` edge count, but it is not expected to clear the full
`{core, tools}` SCC because `core.rag.RagService` and
`core.llm.SystemPromptBuilder` still import `tools`.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T820 HEAD:
  `a8853667`
- Talos version: `0.10.5`
- Prior scoping ticket:
  `work-cycle-docs/tickets/done/[T819-done-high] core-tools-cycle-edge-scoping.md`
- Prior scoping report:
  `work-cycle-docs/reports/t819-core-tools-cycle-edge-scoping.md`

## Scope

- Add neutral `core.context.ContextPrivacyClass`.
- Change `ContextItem` to use `ContextPrivacyClass`.
- Remove `ContextItem.fromToolResult(...)` from `core.context`.
- Add package-private runtime adapter
  `runtime.toolcall.ToolResultContextItemAdapter`.
- Update `ToolCallExecutionStage` to record context ledger items through the
  runtime adapter.
- Update `ContextItem.fromText(...)` callers and tests to use
  `ContextPrivacyClass`.

## Non-Goals

- No `RagService` / `ToolProtocolText` seam work.
- No `SystemPromptBuilder` / `ToolRegistry` seam work.
- No public CLI/product behavior change.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.
- No claim that T820 clears the full package SCC.

## Invariants

- Preserve context source classification for normal tools, `talos.run_command`,
  RAG index/retrieve metadata, and command metadata.
- Preserve execution boundary classification.
- Preserve privacy class names in generated summaries and traces.
- Preserve metadata source-path fallback and protected path token redaction.
- Preserve output hash, character, byte, line, and token count behavior.

## Completion Evidence

Completed in two commits:

- Production cycle-seam extraction:
  `0fea1ef0f5d037727b20f4f686936afda560b804`
- Privacy enum mapping guard:
  `40fc721d84bda76c404b7f884178d2e336d0e04c`

- Added neutral `core.context.ContextPrivacyClass`.
- Removed `dev.talos.tools.*` imports from `core.context`.
- Removed `ContextItem.fromToolResult(...)`.
- Added package-private `runtime.toolcall.ToolResultContextItemAdapter`.
- Repointed context-ledger recording in `ToolCallExecutionStage`.
- Replaced the old core fallback test with runtime adapter coverage.
- Added explicit parity coverage proving `ContextPrivacyClass` and
  `ToolContentMetadata.ContentPrivacyClass` stay name-compatible for the
  adapter's privacy mapping.
- Generated architecture evidence after implementation:
  - `core -> tools` reduced from 8 to 4;
  - `{core, tools}` SCC remains, as expected, because `RagService` and
    `SystemPromptBuilder` still import `tools`.
- No `ContextItem.fromToolResult(...)` call sites remain.
- No `dev.talos.tools.*` imports remain in `core.context`.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolResultContextItemAdapterTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat test --tests "dev.talos.architecture.LayeredArchitectureTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
rg "^import dev\.talos\.tools" src/main/java/dev/talos/core/context src/test/java/dev/talos/core/context
rg "ContextItem\.fromToolResult" src/main src/test
git diff --check
git status --short -- . ':!site'
```
