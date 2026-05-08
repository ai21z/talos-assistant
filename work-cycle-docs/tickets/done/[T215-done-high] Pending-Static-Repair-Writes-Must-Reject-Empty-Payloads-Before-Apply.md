# T215 - Pending Static Repair Writes Must Reject Empty Payloads Before Apply

Status: done
Severity: high

## Problem

The T214 focused audit confirmed that selector facts now reach the bounded static repair prompt, but Qwen still emitted a `talos.write_file` call for `styles.css` with empty content.

Talos allowed that write to execute. Static verification caught the zero-byte stylesheet and failure-dominant output prevented false success, but the workspace was still left with a destructive empty file.

This was not another prompt wording problem. It was a runtime validation gap in the pending static repair action boundary.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t214-bounded-selector-facts-re-audit-20260508-031613/FINDINGS-LLAMA-CPP-T214-BOUNDED-SELECTOR-FACTS-RE-AUDIT.md`

Qwen trace:

`local/manual-testing/llama-cpp-t214-bounded-selector-facts-re-audit-20260508-031613/.home-QWEN-14B/.talos/sessions/traces/7c16cdf98329baccfa5f82f5670205283b6e49cf/000002-trc-dc117144-26fe-40ee-bdd4-ec07280ac755.json`

Key trace facts:

- `PENDING_ACTION_OBLIGATION_RAISED`
- `TOOL_CALL_PARSED` for `talos.write_file`
- `pathHint: styles.css`
- `contentBytes: 0`
- `contentLines: 0`
- write executed successfully
- static verification later failed because `styles.css` was empty

## Scope Completed

- Pending static repair obligations now reject `talos.write_file` calls for remaining repair targets when content is missing, empty, blank, or a template placeholder.
- Rejection happens before approval, checkpoint, and file write.
- The invalid repair write is converted into a deterministic pending-obligation breach.
- Failure detail names the affected path and explains that the write was rejected before apply.
- Existing file content is preserved.
- Normal empty-file behavior outside pending static repair was left unchanged.

## Verification

RED first:

- `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.pendingStaticRepairRejectsEmptyWriteBeforeApply --no-daemon`

The test initially failed because the empty repair write overwrote `styles.css`.

GREEN:

- `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.pendingStaticRepairRejectsEmptyWriteBeforeApply --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`

Focused audit:

`local/manual-testing/llama-cpp-t215-empty-repair-write-re-audit-20260508-033220/FINDINGS-LLAMA-CPP-T215-EMPTY-REPAIR-WRITE-RE-AUDIT.md`

Audit result:

- Qwen did not reproduce the empty-write payload in this run; it wrote non-empty `styles.css` and `scripts.js`, then failed static verification with failure-dominant output.
- GPT-OSS attempted an off-target repair write to `src/__init__.py`; Talos blocked it as a pending static repair obligation breach before file creation.
- The exact destructive empty-write shape remains covered by the deterministic regression test.

## Follow-Up

The T215 audit exposed a separate prompt-context defect: initial static repair context may be injected before workspace-aware selector facts can be added. That should be handled in a new ticket.
