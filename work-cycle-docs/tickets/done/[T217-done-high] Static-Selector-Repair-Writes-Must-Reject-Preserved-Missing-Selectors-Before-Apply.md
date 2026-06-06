# T217 - Static Selector Repair Writes Must Reject Preserved Missing Selectors Before Apply

Status: done
Severity: high

## Problem

The T216 focused audit confirmed that the first static repair prompt now carries the current selector facts. The remaining failure is not missing prompt context: the runtime can know that a target-specific repair is still preserving a verifier-known orphan selector, but it currently allows the write and only catches the problem after mutation.

Example shape:

- Static verifier reports `CSS references missing class selectors: .button`.
- Repair context narrows the full-file replacement target to `styles.css`.
- The model writes a complete `styles.css` replacement that still contains `.button`.
- Talos asks for approval, applies the write, and then static verification fails again.

This is the same class of bug as T215: a repair write is structurally invalid according to runtime-owned facts before apply, so the runtime should reject it before approval and before file mutation.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t216-workspace-aware-selector-facts-re-audit-20260508-041000/FINDINGS-LLAMA-CPP-T216-WORKSPACE-AWARE-SELECTOR-FACTS-RE-AUDIT.md`

Relevant observations:

- Qwen first repair-turn prompt included `[Current static selector facts]`.
- The prompt explicitly showed `Observed in HTML`, `Classes: none`, and `CSS references missing class selectors: .button`.
- Qwen still wrote non-empty repair content that preserved the problematic selector shape, then static verification failed.

Relevant code:

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`

## Scope

- Detect static repair `talos.write_file` calls whose target is a narrowed full-file replacement target.
- Use the current static selector facts already injected into `[Static verification repair context]`.
- Reject target-specific CSS writes that preserve verifier-known missing CSS selectors.
- Reject target-specific JavaScript writes that preserve verifier-known missing JavaScript selectors.
- Reject before approval and before file mutation.
- Record a traceable deterministic action-obligation failure.
- Keep successful valid repair writes unchanged.

## Non-Goals

- Do not add another prompt wording patch.
- Do not implement a general CSS/JS repair engine.
- Do not reject broad repairs where HTML is also an active full-rewrite repair target and the selector may be made valid by changing HTML in the same bounded repair.
- Do not change the full T61 audit plan.

## Acceptance

- A focused RED test proves the current runtime applies a static repair write that preserves a known missing selector.
- After the fix, the same write is blocked before approval and before apply.
- The final answer is failure-dominant and contains no model-authored success prose.
- The failure reason names the static selector repair breach, target path, and preserved selector.
- The trace records a deterministic action-obligation failure.
- Existing happy-path static repair writes still pass.
