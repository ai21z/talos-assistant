# T214 - CSS Selector Repair Guidance Should Be Target-Mode-Aware

Status: open
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

- Focused tests assert CSS-only repair context includes target-mode-aware guidance.
- The instruction does not tell the model to edit HTML when only CSS targets are in the full-file replacement target set.
- Mixed HTML/CSS/JS repair context still keeps the cross-file coherence guidance.
- Full Gradle build/install passes.
- Run a focused Qwen/GPT-OSS audit against the CSS-only selector repair probe and compare with the T213 audit.

## Non-Goals

- Do not weaken static verification.
- Do not broaden CSS-only repair back to full HTML/CSS/JS unless code inspection proves that is the better design.
- Do not add another model retry loop.
- Do not change the pending action-obligation gate.
