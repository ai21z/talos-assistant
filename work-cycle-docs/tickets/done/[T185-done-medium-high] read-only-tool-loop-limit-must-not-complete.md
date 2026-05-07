# T185 - Read-Only Tool-Loop Limit Must Not Complete As Answered Output

Severity: medium-high

## Problem

The focused Qwen/GPT-OSS audit showed GPT-OSS reaching the tool-call/iteration limit during a read-only verify question, while Talos still recorded the turn as complete and answered.

Observed output:

```text
[Used 6 tool(s): talos.read_file | 10 iteration(s)] [iteration limit reached]

[Tool-call limit reached. Some tool calls were not executed.]
```

Trace outcome:

```text
Status: COMPLETE
Outcome: READ_ONLY_ANSWERED
```

That is not a truthful final state. If a read-only turn hits the tool-call limit before producing a grounded or runtime-owned answer, Talos should not classify it as a completed answer.

## Evidence

Audit:
`local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/FINDINGS-T179-T183-FOCUSED-TRUTHFULNESS-AUDIT.md`

GPT-OSS transcript:
- `local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1144-1147`
- `local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1153-1155`

Related earlier work:
- T122 handled repair/fix read-only loop budget before mutation retry.
- This ticket covers generic read-only or verify-only evidence turns that exhaust the tool-call loop without a runtime-owned answer.

## Scope

- Detect read-only/verify-only tool-loop limit exhaustion as a typed incomplete outcome when no deterministic runtime-owned answer is produced.
- Make the visible output failure/advisory-dominant instead of treating the limit message as a successful answer.
- Record a trace warning or outcome reason that distinguishes:
  - tool-loop limit reached but deterministic runtime-owned answer produced,
  - tool-loop limit reached with no grounded answer.
- Preserve successful read-only turns that produce a grounded answer.
- Preserve T184 behavior: if a static import runtime-owned answer can be generated from workspace evidence, that path may complete as grounded even if the model exhausted tool calls.

## Acceptance

- Scripted read-only/verify-only test: repeated `talos.read_file` calls reach the tool-call limit and the model provides no useful answer. Final outcome is not `READ_ONLY_ANSWERED`.
- Visible output says the read-only evidence path did not complete because the tool-call limit was reached.
- Trace includes a machine-readable warning or outcome reason for read-only tool-loop limit exhaustion.
- If a deterministic runtime-owned answer is available after the loop, the final answer may complete as grounded and should record the grounding override.
- Existing repair-loop budget tests remain passing.

## Non-Goals

- Do not change mutation repair loop behavior already covered by T122/T151/T152.
- Do not broaden this into a full planner or retry redesign.
- Do not implement model/provider-specific forcing here.

## Completion Notes

Implemented on `v0.9.0-beta-dev`.

Fix:
- Read-only or verify-only turns that hit the tool-loop iteration limit without a deterministic runtime-owned answer now produce failure/advisory-dominant output instead of preserving model-authored success prose.
- The outcome is classified as `ADVISORY_ONLY`.
- A machine-readable `READ_ONLY_TOOL_LOOP_LIMIT` warning is recorded in the task outcome and local trace.
- Deterministic runtime-owned answers remain allowed to complete when available, preserving the T184 static import override path.

Verification:
- Added classifier coverage for read-only loop limit exhaustion with model success prose.
- Added executor/trace coverage that records `READ_ONLY_TOOL_LOOP_LIMIT`.
- Targeted read-only loop limit tests passed.
- Full `gradlew test`, `gradlew build`, and `gradlew installDist` passed.
- Focused clean Qwen/GPT-OSS rerun did not reproduce the no-runtime-answer limit case on the README/config comparison turn; both models read `README.md` and `config.json` and answered from current evidence.
- GPT-OSS did hit the loop limit on the static import turn, but T184 produced the deterministic runtime-owned answer and preserved it in prompt-debug history, which is the intended exception for runtime-grounded output.
