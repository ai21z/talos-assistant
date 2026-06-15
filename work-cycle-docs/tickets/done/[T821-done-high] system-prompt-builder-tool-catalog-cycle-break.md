# T821 SystemPromptBuilder Tool-Catalog Cycle Break

Status: done
Priority: high
Wave: 5
Owner: architecture/core-tools package boundary

## Summary

Remove the `core.llm.SystemPromptBuilder` dependency on concrete executable
tool registry types by introducing a prompt-facing neutral tool descriptor.

T821 is behavior-preserving architecture work. It is the second production step
in the `core -> tools` cycle-break arc after T820. It should reduce the
generated `core -> tools` edge count, but it is not expected to clear the full
`{core, tools}` SCC because `core.rag.RagService` still depends on
tool-protocol rendering.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T821 HEAD:
  `dc46bef02fec1e472452cfd382d467ba27166dcf`
- Talos version: `0.10.5`
- Prior cycle-seam ticket:
  `work-cycle-docs/tickets/done/[T820-done-high] context-item-tool-result-adapter-cycle-break.md`

## Scope

- Add neutral `core.llm.PromptToolDescriptor`.
- Change `SystemPromptBuilder` to accept prompt-facing descriptors through
  `withPromptTools(...)`.
- Remove `ToolRegistry` and `ToolDescriptor` imports from `core.llm`.
- Add runtime adapter `runtime.toolcall.PromptToolDescriptors` that maps the
  existing executable `ToolRegistry` to prompt descriptors.
- Repoint `AskMode`, `RagMode`, `UnifiedAssistantMode`, and `PromptInspector`
  through the runtime adapter.
- Update prompt-builder tests to use neutral descriptors directly.
- Add an exact golden assertion for the rendered tool descriptor block before
  relying on the extraction.

## Non-Goals

- No `RagService` / `ToolProtocolText` seam work.
- No native `ToolSpec` planning changes.
- No `ToolSurfacePlanner` changes.
- No tool execution or registry behavior changes.
- No public CLI/product behavior change.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.
- No claim that T821 clears the full package SCC.

## Invariants

- Preserve prompt tool descriptor rendering byte-for-byte for existing
  `SystemPromptBuilder` output.
- Preserve read-only and command-tool filtering behavior.
- Preserve native-vs-fallback prompt behavior.
- Preserve existing tool descriptor ordering from the registry adapter.
- Preserve executable tool registry ownership outside `core.llm`.

## Implementation Evidence

Current implementation batch:

- Added `core.llm.PromptToolDescriptor`.
- Replaced `SystemPromptBuilder.withTools(...)` with
  `withPromptTools(...)`.
- Removed executable tool-registry imports from production `core.llm`.
- Added `runtime.toolcall.PromptToolDescriptors` as the registry adapter.
- Repointed `AskMode`, `RagMode`, `UnifiedAssistantMode`, and
  `PromptInspector` through the runtime adapter.
- Updated `SystemPromptBuilderTest` to use neutral prompt descriptors and
  added an exact rendered tool-descriptor block assertion.
- Repointed `NativeToolPipelineTest` through the runtime adapter.
- Local regenerated architecture evidence after the implementation shows
  `core -> tools = 1` and the `{core, tools}` SCC still present, as expected,
  because `RagService -> ToolProtocolText` remains.

Source hygiene note:

- Production `core.llm` has no `dev.talos.tools.*` import and
  `SystemPromptBuilder` no longer references `ToolRegistry` or executable
  `ToolDescriptor`.
- Two pre-existing `src/test/java/dev/talos/core/llm` executor/native-surface
  tests still import concrete tools as test fixtures. They are not production
  cycle edges and are outside T821's production seam.

## Completion Evidence

- Implementation commit:
  `d3d548a02a5fffa11b973000b960eee98808b18c`.
- Production `core.llm` no longer imports `dev.talos.tools.*`.
- `SystemPromptBuilder` consumes neutral `PromptToolDescriptor` values via
  `withPromptTools(...)`.
- `PromptToolDescriptors` adapts executable `ToolRegistry` descriptors from
  `runtime.toolcall`.
- Exact prompt tool-rendering golden coverage exists in
  `SystemPromptBuilderTest`.
- Regenerated architecture evidence shows `core -> tools = 1`; the remaining
  production edge is `core.rag.RagService -> tools.ToolProtocolText`.
- The `{core, tools}` SCC remains until T822 removes the final
  `RagService` seam.
- T822 is the next planned cycle-break ticket.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.core.llm.SystemPromptBuilderTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.NativeToolPipelineTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.prompt.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat test --tests "dev.talos.architecture.LayeredArchitectureTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
rg "^import dev\.talos\.tools" src/main/java/dev/talos/core/llm src/test/java/dev/talos/core/llm
rg "ToolRegistry|ToolDescriptor" src/main/java/dev/talos/core/llm/SystemPromptBuilder.java
git diff --check
git status --short -- . ':!site'
```
