# T744 - Batch Native Operations Array And JSON-Preserving Converters

Status: open
Severity: medium
Release gate: supports T280/T284 bank stability (removes the double-encoding amplifier)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

`talos.apply_workspace_batch` requires `operations_json`: a JSON array encoded
INSIDE a JSON string — double encoding, the hardest emission task in the bank
for a q4 14B model (the harness's own scripted fixture needs triple-escaping
to express one copy operation). Two of three full-bank failures concentrated
on this scenario. A native array parameter removes the amplifier and lets the
T739 grammar constrain every key and brace — but the argument converters
destroy container values today, so the converters must be fixed first.

## Evidence Analysis

- Schema: `runtime/workspace/BatchWorkspaceApplyTool.java:36-39` —
  `operations_json` is `{"type":"string"}` whose content must itself be a JSON
  array; harness fixture triple-escapes
  (`SynchronizedApprovalAuditMain.java:1974-1976`).
- Parser is already ready: `WorkspaceBatchPlanParser.java:29-31` accepts an
  array root or `{operations:[...]}` object.
- **Converter gap (blocking precondition):**
  - `runtime/ToolCallParser.extractParams:424` uses
    `entry.getValue().asText("")` — Jackson returns `""` for container nodes,
    so an array argument silently becomes empty string.
  - `runtime/toolcall/ToolCallSupport.convertNativeToolCalls:58-70` uses
    `String.valueOf(entry.getValue())` — CompatChatClient deserializes
    arguments to `Map<String,Object>` (CompatChatClient.java:353-375), so a
    list value stringifies as Java `toString` (`[{op=mkdir, path=docs}]`),
    not JSON.
- Without the converter fix, advertising an array parameter actively breaks
  batch calls — worse than the status quo.

## Architectural Hypothesis

Tool arguments must survive the wire as JSON regardless of nesting; the
converters should JSON-stringify container values (Jackson
`writeValueAsString` / `JsonNode.toString`) so string-typed tool params can
carry structured content losslessly, enabling a native array schema with a
legacy string fallback.

## Architecture Metadata

Capability: native tool-call argument conversion + batch tool schema
Operation(s): apply_workspace_batch (converters affect all tools generically)
Owning package/class: `dev.talos.runtime.ToolCallParser`,
`dev.talos.runtime.toolcall.ToolCallSupport`,
`dev.talos.runtime.workspace.BatchWorkspaceApplyTool`
New or changed tools: schema change (additive) on `talos.apply_workspace_batch`
Risk, approval, and protected paths:
  - Risk level: WRITE unchanged; approval behavior unchanged
Checkpoint, evidence, verification, and repair: unchanged
Outcome and trace:
  - Trace/debug fields: pathHint/parameterNames may show `operations`
Refactor scope: the three named classes + tests + (if needed) harness scenario
fixture remains on legacy form for back-compat proof

## Required Behavior

1. Converters preserve JSON: container-valued arguments (object/array) are
   serialized with Jackson (`writeValueAsString`/`node.toString()`), never
   `asText("")` or `String.valueOf` — in BOTH `ToolCallParser.extractParams`
   and `ToolCallSupport.convertNativeToolCalls`. Scalars unchanged.
2. `BatchWorkspaceApplyTool` schema gains a native `operations` array
   parameter (op/from/to/path/new_name/overwrite/recursive object items);
   `operations_json` string remains accepted (legacy). Execute() normalizes:
   structured `operations` → serialized form → existing
   `WorkspaceBatchPlanParser` path (unchanged).
3. Tool description documents both forms, array preferred.

## Non-Goals

- No removal of `operations_json` (legacy harness fixtures + old transcripts
  rely on it).
- No changes to WorkspaceBatchPlanParser semantics.

## Tests

- Converter round-trip tests: array/object/nested arguments survive both
  conversion paths as valid JSON strings; scalars unchanged (regression).
- `BatchWorkspaceApplyToolTest`: execute with native array form; execute with
  legacy string form; both produce identical plans.
- Schema test: descriptor advertises `operations` array + legacy param.

## Acceptance Criteria

- Focused tests green for ToolCallParser/ToolCallSupport/BatchWorkspaceApplyTool.
- Full `test` lane green (converter change is generic — watch for fixture
  fallout).
- Scripted sync scenario `workspace-batch-apply-approved` still passes
  (`runSynchronizedApprovalAudit` scripted mode) proving legacy form intact.
- CHANGELOG `## [Unreleased]` gains a T744 entry.

## Known Risks

- The converter change touches every native tool call; the generic round-trip
  tests and full unit lane are the guard. Land BEFORE the T746 banks so the
  bank exercises the shipping surface.

## 2026-06-10 completion evidence

- Implemented: `ToolCallParser.extractParams` preserves container nodes as
  JSON (`jsonParamValue`; `asText("")` previously returned "" for arrays);
  `ToolCallSupport.convertNativeToolCalls` serializes Map/List arguments via
  Jackson (`String.valueOf` previously produced non-JSON `toString`);
  `BatchWorkspaceApplyTool` descriptor now advertises a native `operations`
  array (required, item schema with op enum) plus the legacy `operations_json`
  string; `WorkspaceBatchPlanParser` already accepted the `operations` param
  key, so execution needed no change.
- Note: schema `required:["operations"]` constrains live grammar-enforced
  model emissions only — tool execution validates via the parser, so legacy
  scripted fixtures and old transcripts using `operations_json` keep working
  (proven by the unchanged scripted bank tests).
- Tests green: ToolCallParserTest +1 (container preservation),
  ToolCallSupportTest +2 (container JSON, scalar legacy),
  BatchWorkspaceApplyToolTest +2 (native array execution identical to legacy;
  schema advertisement). Full `test` + `e2eTest` lanes BUILD SUCCESSFUL
  (2m10s) — no converter fallout across the 4799-test suite.
