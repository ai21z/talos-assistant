# T822 Rag Tool-Protocol Text Cycle Break

Status: open
Priority: high
Wave: 5
Owner: architecture/core-tools package boundary

## Summary

Remove the final production `core -> tools` dependency by moving non-executing
tool-protocol text cleanup behind a neutral core owner.

T822 is behavior-preserving architecture work. It is the final planned
production step in the `core -> tools` cycle-break arc after T820 and T821. It
should reduce generated `core -> tools` to `0` and clear the `{core, tools}`
SCC if no other production edge is introduced.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T822 HEAD:
  `ed5fd4f081ad45c21bd00bd60e6781872b6efde6`
- Talos version: `0.10.5`
- Prior cycle-seam ticket:
  `work-cycle-docs/tickets/done/[T821-done-high] system-prompt-builder-tool-catalog-cycle-break.md`

## Scope

- Add neutral `core.tool.ToolNamePolicy`.
- Keep `tools.ToolAliasPolicy` and `tools.BackendToolProfile` as compatibility
  API wrappers around the core policy.
- Move protocol text cleanup implementation to `core.tool.ToolProtocolText`.
- Keep `tools.ToolProtocolText` as a compatibility wrapper around the core
  cleanup owner.
- Repoint `core.rag.RagService` to the core cleanup owner.
- Repoint `runtime.ToolCallParser` to the core cleanup owner while preserving
  existing parser behavior.
- Update source-ownership tests so production `core` no longer imports
  `dev.talos.tools.*`.

## Non-Goals

- No Qodana changes.
- No candidate recut.
- No `SetupCmd.java` edits.
- No tool execution behavior changes.
- No registry behavior changes.
- No broad runtime alias-policy caller migration.
- No relocation of test-only `core.llm` fixture packages unless it is trivial
  and isolated.

## Invariants

- Preserve every current `stripToolCalls(...)` behavior, including XML tags,
  code-fenced JSON, bare JSON, malformed protocol debris, standalone JSON, and
  adversarial backtracking resistance.
- Preserve accepted aliases and backend profile classification.
- Preserve `ToolCallParser` stripping/detection behavior.
- Preserve `RagService` defensive cleanup of generated RAG answer text.
- Preserve compatibility for existing `tools.ToolAliasPolicy` and
  `tools.ToolProtocolText` callers.

## Acceptance

- Production `core` has no `dev.talos.tools.*` imports.
- Regenerated architecture evidence reports `core -> tools = 0`.
- Regenerated architecture evidence no longer contains the `{core, tools}` SCC.
- `validateArchitectureBoundaries`, `LayeredArchitectureTest`, `check`, and
  `wikiEvidenceCloseGate --rerun-tasks` pass.

## Implementation Evidence

Current implementation batch:

- Added `core.tool.ToolNamePolicy` as the neutral owner for canonical Talos
  tool names, accepted aliases, read-only/mutating classification, backend
  profile labels, and alias-token detection.
- Moved non-executing tool-protocol text cleanup into
  `core.tool.ToolProtocolText`.
- Kept `tools.ToolAliasPolicy`, `tools.BackendToolProfile`, and
  `tools.ToolProtocolText` as compatibility API surfaces; the two policy/text
  facades now delegate to core owners.
- Repointed `core.rag.RagService` and `runtime.ToolCallParser` to
  `core.tool.ToolProtocolText`.
- Moved full protocol-text cleanup coverage to `core.tool` and added
  compatibility coverage in `tools`.
- Updated `RagServiceContextLedgerTest` to assert production `core.rag` no
  longer imports `dev.talos.tools.*`.

Local regenerated architecture evidence after implementation:

- `core -> tools = 0`.
- `tools -> core = 42`.
- No non-trivial top-level package SCCs detected.
