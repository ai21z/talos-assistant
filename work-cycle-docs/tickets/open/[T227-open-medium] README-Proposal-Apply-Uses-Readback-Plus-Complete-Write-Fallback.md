# T227 - README Proposal Apply Uses Readback Plus Complete-Write Fallback

Severity: medium

## Problem

The post-T225 broad llama.cpp audit found that Qwen still struggles to apply a prior README proposal. It repeatedly called `talos.edit_file` with invalid `old_string` values and failed the turn after the retry budget.

Prompt:

> Apply that README.md proposal now.

Observed:

- Qwen repeatedly used invalid `talos.edit_file` calls.
- Runtime containment worked: the turn failed cleanly and did not claim success.
- GPT-OSS succeeded on the same task by reading `README.md` and writing a complete replacement.

Evidence:

- `local/manual-testing/llama-cpp-post-t225-broad-product-audit-20260508-082833/FINDINGS-LLAMA-CPP-POST-T225-BROAD-PRODUCT-AUDIT.md`
- `local/manual-testing/llama-cpp-post-t225-broad-product-audit-20260508-082833/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`

## Scope

- For applying a prior proposal to a small Markdown file, make the reliable path readback-first and complete-write-capable.
- Avoid repeated invalid `old_string` loops when the model cannot construct an exact edit.
- Preserve approval, protected path, and failure-dominant behavior.
- Do not generalize into a broad planner.
- Do not weaken exact-write verification or protected-read rules.

## Acceptance

- Tests reproduce an apply-proposal turn where invalid `edit_file` attempts would previously exhaust the retry budget.
- Runtime either steers to readback plus complete `write_file`, or deterministically fails with a clearer proposal-apply reason before repeated invalid loops.
- Successful small Markdown proposal apply remains concise and runtime-auditable.
- Qwen and GPT-OSS focused audit covers README proposal apply.

