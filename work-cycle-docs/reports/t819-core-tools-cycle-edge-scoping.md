# T819 Core-Tools Cycle Edge Scoping

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Commit used for scoping: `d1371693cdf25873a5fa477dd8bce17f5e5c500f`
- Talos version: `0.10.5`
- Generated package report:
  `build/reports/talos/architecture-intelligence/current/03-package-boundary-and-cycle-map.md`
- Generated data source:
  `build/reports/talos/architecture-intelligence/current/data/dependency-cycle-map.json`

T819 is report-only. It does not authorize production cycle surgery.

## Generated Package Evidence

| Evidence | Current value | Meaning |
|---|---:|---|
| Top-level SCC count | 1 | Only one package strongly connected component is currently reported. |
| SCC members | `core`, `tools` | The remaining top-level cycle is between core and tools. |
| `core -> tools` generated edges | 8 | Smaller generated direction to scope first. |
| `tools -> core` generated edges | 40 | Larger reverse direction; not the first assumed break target. |

`runtime.toolcall` remains important Wave 5 ownership pressure, but it is not
part of this top-level SCC. Do not conflate tool-loop ownership work with the
`core <-> tools` package cycle.

## Explicit `core -> tools` Import Sites

| Core owner | Tool imports | Current ownership signal |
|---|---|---|
| `core.context.ContextItem` | `ToolContentMetadata`, `ToolResult` | Core context/ledger model knows concrete tool result and tool metadata types through `fromToolResult(...)`. |
| `core.rag.RagService` | `ToolContentMetadata`, `ToolProtocolText` | RAG context assembly uses tool privacy metadata and strips tool protocol text from model answers. |
| `core.llm.SystemPromptBuilder` | `ToolDescriptor`, `ToolRegistry` | Core prompt builder renders tool descriptions directly from the tool registry. |

The source-level smaller direction is 3 core files / 6 explicit imports. That
does not prove the fix is cheap. It only identifies the first direction worth
scoping.

## Candidate Seam Review

### 1. `ContextItem.fromToolResult(...)` Adapter Seam

Likely first T820 candidate.

Current pressure: `core.context.ContextItem` imports both `ToolResult` and
`ToolContentMetadata` so core context construction depends on concrete tool
result types.

Potential move: keep `ContextItem` as a core context model, but move
tool-result-to-context conversion to a tool/runtime adapter or introduce a
neutral context metadata contract.

Why first: this can remove the strongest concrete tool-result dependency from
core without moving the whole tool model.

Risk: privacy/source/boundary metadata semantics must remain byte-equivalent.

### 2. `ToolContentMetadata` Neutralization Seam

Potential follow-up, not automatically first.

Current pressure: both context and RAG need content provenance and privacy
classification, but the type currently lives under `tools`.

Potential move: extract a lower-level neutral metadata record only if the
T820 adapter seam cannot remove the cycle cleanly.

Risk: this type is part of tool-result semantics. Moving it blindly could
spread tool policy concepts into lower packages instead of clarifying them.

### 3. `SystemPromptBuilder` Tool Description Seam

Potential prompt-surface seam.

Current pressure: `core.llm.SystemPromptBuilder` stores a `ToolRegistry` and
renders `ToolDescriptor` values directly.

Potential move: pass a neutral rendered tool-surface description or a narrow
descriptor view into the prompt builder instead of importing the concrete tool
registry.

Risk: prompt construction and tool-surface narrowing are policy-sensitive.
This seam should not be changed without preserving current tool-list rendering
tests.

### 4. `ToolProtocolText` Sanitizer Seam

Do not move blindly.

Current pressure: `core.rag.RagService` imports `ToolProtocolText` to strip
tool protocol text from RAG answer content.

Potential move: introduce a neutral sanitizer boundary or pass already-cleaned
text into RAG assembly.

Risk: `ToolProtocolText` is tied to tool alias/protocol parsing behavior. A
casual move can weaken protocol cleanup or create a worse package dependency.

## T820 Decision Gate

T820 should not begin by moving packages broadly. It should start from one
chosen seam and prove that the cycle break is behavior-preserving.

Recommended first T820 target: split the `ContextItem.fromToolResult(...)`
adapter boundary, unless a fresh source inspection shows that
`SystemPromptBuilder` can eliminate the cycle with lower blast radius.

T820 acceptance should include:

- package cycle map shows no top-level `{core, tools}` SCC, or explicitly
  explains any remaining edge;
- `core -> tools` is reduced to 0 if the selected seam is sufficient;
- `validateArchitectureBoundaries` passes;
- `LayeredArchitectureTest` passes;
- `check` passes;
- no public CLI/product behavior changes.

## Non-Goals

- No production changes in T819.
- No `LoopResult` or `ToolOutcome` relocation.
- No runtime tool-loop ownership extraction.
- No Qodana changes.
- No candidate recut.
