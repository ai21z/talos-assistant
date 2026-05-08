# T214 - CSS Selector Repair Guidance Should Be Target-Mode-Aware

Status: done
Severity: medium

## Problem

T213 correctly narrows CSS-only static repair to `styles.css`, but the focused audit showed Qwen still rewriting the stylesheet while preserving the orphan selector that caused the verifier failure.

When the repair target set is stylesheet-only, Talos currently says the model must rewrite `styles.css`, but it does not explicitly explain the constrained repair strategy: do not rely on changing HTML; change or remove CSS selectors so they match classes/IDs that actually exist in the current HTML.

This is a prompt-quality issue after the target-selection fix, not a failure-dominance or action-obligation issue.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t213-focused-re-audit-20260508-020613/FINDINGS-LLAMA-CPP-T213-FOCUSED-RE-AUDIT.md`

Relevant transcript lines:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:783`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:800`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1095`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1239`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1503`

## Scope

- Improve static repair instruction text for CSS selector-source problems.
- When repair is narrowed to CSS targets only, explicitly state that the model must repair the stylesheet without depending on HTML edits.
- Tell the model that missing CSS class/id selector findings are satisfied by changing/removing/renaming stylesheet selectors so they correspond to existing HTML classes/IDs.
- Keep the verifier-specific target narrowing from T213.
- Keep failure-dominant output and pending obligation breach behavior unchanged.

## Acceptance

- Done: focused tests assert CSS-only repair context includes target-mode-aware guidance.
- Done: the instruction does not tell the model to edit HTML when only CSS targets are in the full-file replacement target set.
- Done: mixed HTML/CSS/JS repair context still keeps the cross-file coherence guidance.
- Done: current selector facts are injected into both the initial static repair context and the bounded pending-action repair continuation.
- Done: full Gradle build/install passes.
- Done: focused Qwen/GPT-OSS audit confirmed prompt-debug capture includes selector facts in the pending repair continuation.

## Non-Goals

- Do not weaken static verification.
- Do not broaden CSS-only repair back to full HTML/CSS/JS unless code inspection proves that is the better design.
- Do not add another model retry loop.
- Do not change the pending action-obligation gate.

## Completion Notes

Implemented in commit pending from this ticket:

- Added target-mode-aware CSS-only selector repair guidance.
- Added target-aware selector fact rendering for audit-shaped static workspaces where non-web fixture files would otherwise block small-workspace selector inspection.
- Shared selector-fact repair enrichment through `RepairPolicy`.
- Wired the same selector facts into the bounded `ToolCallRepromptStage` static repair continuation.
- Added prompt-debug regression coverage for the bounded continuation path.

Verification:

- `.\gradlew.bat test --tests dev.talos.core.llm.ToolCallRepromptStagePromptDebugTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.core.llm.ToolCallRepromptStagePromptDebugTest --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.runtime.repair.RepairPolicyTest --tests dev.talos.runtime.ToolCallLoopTest --tests dev.talos.runtime.verification.StaticTaskVerifierTest --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`

Focused audit:

- `local/manual-testing/llama-cpp-t214-bounded-selector-facts-re-audit-20260508-031613/FINDINGS-LLAMA-CPP-T214-BOUNDED-SELECTOR-FACTS-RE-AUDIT.md`

Audit result:

- Prompt construction is fixed for T214.
- Qwen still produced an empty `styles.css` write during repair.
- GPT-OSS still failed to satisfy pending repair target writes.
- Runtime containment worked in both cases.
- Follow-up should target pre-apply validation of empty/placeholder repair writes, not more T214 wording.
