# T851 Read-Display Line-Number Write Containment

Status: implemented; awaiting owner/live review before ticket closeout
Date: 2026-06-21
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Summary

T851 adds a narrow pre-approval containment guard for a T842 manual-audit
failure where GPT-OSS wrote Talos `read_file` display text such as
`1 | def bar():` back into a source file.

This is a runtime-owned mutation-boundary fix. It does not change `read_file`
display formatting, semantic verification, approval policy, or file tool disk
write semantics.

## Boundary

Moved into production:

- `ReadDisplayWriteContainmentGuard` in `dev.talos.runtime.toolcall`.
- A `ToolCallPreExecutionGuardChain` call site that blocks contaminated
  mutation payloads before approval.

Not moved or changed:

- `ReadFileTool` output shape.
- `FileWriteTool` and `FileEditTool` disk write behavior.
- Approval gate semantics.
- Existing exact-write/readback verification.
- T850 read-only grounding and T852 GPT-OSS synthesis behavior.

## Guard Semantics

The guard fires only when all of these are true:

- the tool is `write_file` or `edit_file`;
- the target path has same-turn `read_file` evidence with Talos display lines;
- the mutation payload carries whole-line `N | ...` read-display prefixes.

When it fires, the chain:

- increments failure accounting;
- emits an `INVALID_PARAMS` tool result with a bounded diagnostic;
- records `READ_DISPLAY_WRITE_CONTAINMENT`;
- records the blocked tool call in the local trace;
- adds a failed pre-execution mutation outcome;
- appends the formatted tool result back to the model context;
- returns without approval and without disk mutation.

The guard intentionally does not block literal numbered-pipe text without
same-turn read-display evidence.

## Deterministic Coverage

Added:

- `ReadDisplayWriteContainmentGuardTest`
  - `writeFileBlocksSameTurnReadDisplayPrefixesBeforeApproval`;
  - `editFileBlocksSameTurnReadDisplayReplacementBeforeApproval`;
  - `ordinarySourceWriteIsAllowedAfterSameTurnReadDisplay`;
  - `literalNumberedPipeTextWithoutSameTurnReadDisplayIsAllowed`.
- `ToolCallLoopTest.readDisplayWritePayloadIsBlockedBeforeApprovalAndLeavesFileUnchanged`.

Red-first result:

- Initial focused run failed on the two blocking tests because the chain allowed
  the contaminated payloads.

Focused green result:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ReadDisplayWriteContainmentGuardTest" --tests "dev.talos.runtime.ToolCallLoopTest.readDisplayWritePayloadIsBlockedBeforeApprovalAndLeavesFileUnchanged" --no-daemon
```

Result: PASS.

## Remaining Review Gate

T851 is not closed by this report.

Before closeout, rerun the T842/scn-14 corruption probe on both beta models:

- `qwen2.5-coder:14b` through managed `llama.cpp`;
- `gpt-oss:20b` through managed `llama.cpp`.

Close only if the live evidence shows that the file is not corrupted by
read-display line-number prefixes and no unrelated beta-correctness regression
is introduced.
