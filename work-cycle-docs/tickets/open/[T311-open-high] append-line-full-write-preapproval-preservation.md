# T311 - Append-Line Full-Write Preapproval Preservation

Status: fixed in working tree / pending full gate
Severity: high
Release gate: yes for developer/code beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The Qwen 22-case synchronized approval live audit repeatedly failed the append-line scenario. In each failure, the verifier correctly rejected the final state, but the bad `talos.write_file` call had already reached approval and execution.

The failure class matters because append-line requests are narrow mutations. A full-file write that does not preserve the complete same-turn readback should not reach approval as if it were a valid append operation.

## Evidence from current code

- `TaskExpectationResolver` derives `AppendLineExpectation` for explicit append-line requests.
- `StaticTaskVerifier` already fails bad append-line outcomes after mutation when the prior content is missing or the requested line is not the final logical line.
- `ToolCallExecutionStage` had same-turn complete read evidence available through `successfulReadCallBodies`.
- Before this fix, `ToolCallExecutionStage` did not use that evidence to block invalid full-file append writes before approval.
- `TemplatePlaceholderGuard` blocked several placeholder families, but did not catch all live Qwen placeholder shapes before this ticket's guard expansion.

## Evidence from tests/audits

- Qwen r2 failure:
  - `local/manual-testing/synchronized-approval-live-qwen-20260519-22case-r2/mutation-append-line-verified/`
  - final `README.md` was `<content of README.md>Release gate note`
  - verifier recorded `verificationStatus="FAILED"`
- Qwen r3 failure:
  - `local/manual-testing/synchronized-approval-live-qwen-20260519-22case-r3/mutation-append-line-verified/`
  - final `README.md` was `<read_file_content>\nRelease gate note`
  - verifier recorded `verificationStatus="FAILED"`
- Qwen r4 failure:
  - `local/manual-testing/synchronized-approval-live-qwen-20260519-22case-r4/mutation-append-line-verified/`
  - final `README.md` was `Existing content from README.md\n\nRelease gate note`
  - verifier recorded `verificationStatus="FAILED"`
- Regression tests added:
  - `TemplatePlaceholderGuardTest.leadingToolResultPlaceholderWithAppendedContentIsFlagged`
  - `TurnProcessorPlaceholderGuardTest.writeFileWithLeadingContentOfFilePlaceholderIsRejectedBeforeApproval`
  - `TurnProcessorPlaceholderGuardTest.writeFileWithLeadingReadFileContentPlaceholderIsRejectedBeforeApproval`
  - `ToolCallLoopTest.appendLineFullWriteThatDoesNotPreserveReadbackIsRejectedBeforeApproval`

## User impact

Without this guard, a user can approve what Talos presents as a write operation for an append request while the actual content replaces the file with placeholder or invented prior content. The verifier may catch the failure after the fact, but the workspace has already been damaged and requires rollback.

## Product risk

High. This is an approval-boundary quality problem. Human approval is not a substitute for runtime validation of narrow mutation semantics.

## Runtime boundary affected

Template placeholder guard, tool-call execution stage, append-line expectation enforcement, approval gate, checkpointing, verifier truthfulness, and live audit classification.

## Non-goals

- Do not add an append tool in this slice.
- Do not rely on user approval preview as the safety boundary.
- Do not make append-line verification weaker just to let live models pass.

## Required behavior

For explicit append-line requests, `talos.write_file` must be rejected before approval unless the write content preserves the complete same-turn readback and appends exactly the requested line as the final logical line. Placeholder families such as `<content of README.md>` and `<read_file_content>` must also be rejected before approval.

## Proposed implementation

- Extend `TemplatePlaceholderGuard` to catch:
  - `<content of README.md>...`
  - `<read_file_content>...`
  - other angle-bracket content/read-file placeholder prefixes.
- Add a `ToolCallExecutionStage` pre-approval guard for append-line `write_file` calls:
  - resolve `AppendLineExpectation`;
  - require complete same-turn read evidence for the target;
  - compare the proposed write content against prior readback plus the requested appended line;
  - reject with `INVALID_PARAMS` before approval if preservation does not hold.

## Tests

- `TemplatePlaceholderGuardTest`
- `TurnProcessorPlaceholderGuardTest`
- `ToolCallLoopTest.appendLineFullWriteThatDoesNotPreserveReadbackIsRejectedBeforeApproval`
- Focused synchronized approval e2e and scripted audit.

## Acceptance criteria

- Placeholder append writes are rejected before approval.
- Invented-prior-content append writes are rejected before approval.
- Valid append-line writes still pass scripted and live synchronized approval audits.
- Qwen 22-case live synchronized approval audit passes the append-line scenario.
- Runtime artifact scans pass on generated scripted and live roots.

## Remaining blockers

- Full `clean check e2eTest` still needs to be rerun after the complete blocker batch.
- Full prompt-bank audit remains broader than this synchronized approval slice.

## Open questions

- Should Talos add a dedicated append-line tool or operation profile so models do not have to perform full-file append rewrites?
- Should approval previews explicitly label narrow semantic guards such as append-line preservation?

## Related files

- `src/main/java/dev/talos/runtime/TemplatePlaceholderGuard.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/expectation/AppendLineExpectation.java`
- `src/test/java/dev/talos/runtime/TemplatePlaceholderGuardTest.java`
- `src/test/java/dev/talos/runtime/TurnProcessorPlaceholderGuardTest.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditMain.java`
