# T227 - README Proposal Apply Uses Readback Plus Complete-Write Fallback

Status: done

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

- Tests cover the exact audited phrase `Apply that README.md proposal now.` consuming saved proposal context.
- Prompt-frame tests cover `[ProposalApply]` guidance for active Markdown proposal application.
- Executor integration verifies the exact audited phrase carries active proposal context and read/write tools.
- Focused Qwen and GPT-OSS audit covered README proposal apply.

## Completion Evidence

- Added active-context recognition for targeted proposal-apply phrases such as `Apply that README.md proposal now.`
- Added `[ProposalApply]` current-turn guidance for active Markdown/README proposal application.
- Qwen focused audit passed: apply turn used `talos.read_file` then `talos.write_file`; no repeated invalid `old_string` loop.
- GPT-OSS focused audit passed: apply turn used `talos.read_file` then `talos.edit_file`; no repeated invalid `old_string` loop.
- Findings: `local/manual-testing/t227-readme-proposal-apply-focused-audit-20260508-092510/FINDINGS-LLAMA-CPP-T227-README-PROPOSAL-APPLY.md`

## Verification

- `.\gradlew.bat test --tests dev.talos.runtime.context.ActiveTaskContextPolicyTest.applyThatReadmeProposalConsumesProposalContext --tests dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest.renderIncludesProposalApplyReadbackWriteGuidanceForActiveMarkdownProposal --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.context.ActiveTaskContextPolicyTest --tests dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`
- `python local\manual-testing\t227-readme-proposal-apply-focused-audit-20260508-092510\run_t227_readme_proposal_apply_focused_audit.py`
