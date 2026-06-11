# T757 - Metadata-Driven Mutation/Checkpoint Gating, Fail-Closed

Status: done - completed in wave 2; see completion evidence section
Severity: high
Release gate: yes (fail-closed doctrine: gating must not key off hand lists)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: Claude

## Problem

Mutation-intent blocking, the three pre-approval validators (sandbox path,
forbidden target, expected target), and checkpoint capture all keyed off
`ToolCallSupport`'s hand-maintained READ_ONLY_TOOLS/MUTATING_TOOLS name
sets. A registered mutating tool missing from those lists FAILED OPEN: no
intent block on read-only contracts, no pre-approval path validation, no
checkpoint — it executed with approval only. Meanwhile every tool already
declares the truth at registration via `ToolOperationMetadata`
(mutatesWorkspace, requiresCheckpoint). THREE duplicate classification
sources existed (ToolCallSupport lists, per-tool metadata, ToolAliasPolicy
canonical sets). 2026-06-10 evaluation, roadmap item W2.6.

## Architecture Metadata

Capability: trust-gate classification
Operation(s): all mutating operations
Owning package/class: `dev.talos.runtime.TurnProcessor` (gates read the
resolved tool's `ToolOperationMetadata`), new
`dev.talos.runtime.toolcall.ToolMutationGate` (fail-closed name+registry
classification), `dev.talos.tools.ToolAliasPolicy` (single static
name-classification source), `ToolCallSupport` (delegates)
New or changed tools: none
Risk, approval, and protected paths: approval routing unchanged
(effectiveRisk/permission policy untouched); pre-approval validators now
gate on metadata
Checkpoint behavior: keyed off `requiresCheckpoint` (not name lists);
unknown tools via ToolMutationGate → checkpoint-required (fail closed)
Outcome and trace: no event changes
Refactor scope: the named files; PATH_REQUIRED_TOOLS retained in
ToolCallSupport (path-repair concern, future pathRoles derivation noted)

## Required Behavior

1. TurnProcessor derives `opMeta` from the registry-resolved tool (alias
   rescue happens at resolution, so gating sees the real tool, not the raw
   model-emitted name). Intent gate uses `mutatesWorkspace`; checkpoint
   capture uses `requiresCheckpoint`; the three validators receive the
   metadata-derived mutating flag.
2. `ToolMutationGate` for name-only callers: unresolvable name or missing
   registry → mutating=true, checkpoint-required=true (doctrine home,
   AGENTS.md fail-closed list).
3. `ToolCallSupport.isMutatingTool/isReadOnlyTool` delegate to
   `ToolAliasPolicy` (verified equivalent for every pinned shape — the
   alias entries in the deleted lists were dead: classification always
   went through localCanonicalName). ~14 static heuristic/telemetry
   consumers keep the unknown→false default; they are not gates.
4. Behavior-preserving for all 13 registered tools (golden table pinned:
   run_command mutates=false/checkpoint=false, approval via command
   profiles; delete_path DESTRUCTIVE+destructive; the other 8 mutating
   tools checkpoint-required).

## Behavioral deltas (intended)

- A future registered mutating tool absent from any name list is now
  intent-blocked, path-validated, and checkpointed (previously failed open).
- Metadata-mutating stub/test tools with no target path now fail closed at
  checkpoint capture ("Checkpoint requires a target path") — surfaced in
  two ApprovalGatedToolTest stubs, fixed by giving them target paths.
- Workspace escapes by metadata-mutating tools outside the legacy lists
  are now caught by the pre-approval sandbox validator (INVALID_PARAMS,
  "Path not allowed before approval") before the declarative permission
  layer's DENIED — earlier and stricter; SessionApprovalPolicyTest updated
  with the rationale.

## Known Risks / Residual

- Bare `ToolDescriptor(name, description)` defaults to READ_ONLY metadata —
  a registration-side fail-open the gate cannot see. NOT changed this wave
  (would ripple through dozens of test stubs); the ToolMetadataParityTest
  golden table forces every newly registered tool to declare a pinned row.
- TurnProcessor's effectiveRisk/apply_workspace_batch approval special case
  deliberately unchanged — approval routing is not this ticket.

## Tests / Evidence

- `ToolMutationGateTest`: unknown/blank/null → fail-closed; 13-tool
  classification; alias rescue ("writefile", "mv", "tool_use:write_file").
- `ToolMetadataParityTest`: golden metadata row per registered tool;
  registry coverage (unpinned registration fails the build); ToolAliasPolicy
  ↔ metadata parity (the two remaining sources cannot drift silently).
- `TurnProcessorTest` flip pins (fail on pre-T757 code): stub
  `talos.shredder` with mutating metadata is intent-blocked on a read-only
  contract with zero approval prompts, and checkpoint-captured before
  execution on a mutation flow; inspect-metadata stub executes with no
  approval and no checkpoint.
- Existing ToolCallSupportTest pins unchanged (delegation equivalence).

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green (4,851 unit tests).
- READ_ONLY_TOOLS/MUTATING_TOOLS deleted; gating reads metadata; no stray
  checkpoint artifacts from the new stub coverage.
