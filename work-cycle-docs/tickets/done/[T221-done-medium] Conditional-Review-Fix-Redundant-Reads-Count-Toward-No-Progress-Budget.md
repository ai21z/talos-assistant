# T221 - Conditional Review/Fix Redundant Reads Count Toward No-Progress Budget

Status: done
Severity: medium

## Problem

The post-T220 broad llama.cpp audit found a GPT-OSS conditional review/fix turn that inspected the BMI calculator with read-only tools, repeated an already gathered read, and then attempted one more model continuation. That continuation exceeded the local 8k context budget and produced:

`[Action obligation failed: retry could not fit in the context budget.]`

The output is failure-dominant and safe, but the failure class is wrong. Talos already had enough state to stop deterministically as a repair/fix no-progress inspection failure.

## Evidence

Audit:
`local/manual-testing/llama-cpp-post-t220-broad-product-audit-20260508-053200/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`

Relevant area:
- user turn around line 15611: `Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.`
- failure around lines 15632-15640:
  `[Action obligation failed: retry could not fit in the context budget.]`

Code observation:
- `ToolCallExecutionStage` suppresses redundant read-only calls with the `You already gathered this information...` diagnostic.
- `ToolCallRepromptStage.repairReadOnlyBudgetExceeded` counts only `state.toolNames`, so suppressed redundant reads do not count toward the read-only/no-progress repair budget.
- The loop can therefore try another continuation instead of stopping with deterministic `REPAIR_INSPECTION_ONLY`.

## Scope

- Count suppressed redundant read-only calls as no-progress inspection attempts for conditional repair/review budget enforcement.
- Keep the scope inside the tool-loop state machine/accounting.
- Do not change the user-facing prompt wording unless a test shows it is necessary.
- Do not change static verification rules.

## Acceptance

- A conditional review/fix turn that reads relevant files and then repeats already gathered read evidence stops with deterministic `REPAIR_INSPECTION_ONLY`.
- The final output does not contain success claims such as `complete`, `ready to use`, or browser-ready prose.
- The loop does not attempt an extra context-heavy continuation after redundant-read suppression in this case.
- Existing read-then-mutate repair/fix happy paths still pass.

## Verification

- Add a focused regression test reproducing the audit shape.
- Run targeted tool-loop tests.
- Run full Gradle test/build verification.
- Run a focused audit or focused broad-audit slice only if the implementation changes runtime behavior in a way not covered by tests.
