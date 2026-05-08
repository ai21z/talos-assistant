# T216 - Workspace-Aware Static Repair Context Must Carry Selector Facts

Status: done
Severity: high

## Problem

The T215 focused audit exposed a prompt-construction gap in the product path.

`UnifiedAssistantMode` injected `[Static verification repair context]` before the workspace-aware executor call:

- `UnifiedAssistantMode` called `AssistantTurnExecutor.injectStaticVerificationRepairInstruction(messages, taskContract)` without a workspace.
- `AssistantTurnExecutor.execute(...)` later called the workspace-aware overload, but skipped enrichment because a static repair context was already present.

Result: the first repair-turn prompt could contain `Full-file replacement targets:` but miss `[Current static selector facts]`, even though the bounded pending-action repair continuation included those facts.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t215-empty-repair-write-re-audit-20260508-033220/FINDINGS-LLAMA-CPP-T215-EMPTY-REPAIR-WRITE-RE-AUDIT.md`

Qwen prompt debug:

`local/manual-testing/llama-cpp-t215-empty-repair-write-re-audit-20260508-033220/PROMPT-DEBUG-LLAMA-CPP-QWEN-14B/prompt-debug-20260508-033424.md`

Observed before fix:

- `[Static verification repair context]` present.
- `Full-file replacement targets: scripts.js, styles.css` present.
- `[Current static selector facts]` absent.

Code path:

- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`

## Scope Completed

- `UnifiedAssistantMode` now calls the workspace-aware static repair injection overload.
- The first product-path repair prompt receives current selector facts when a workspace is available.
- Existing repair context insertion and target narrowing behavior is preserved.
- Bounded pending-action repair continuation behavior is unchanged.

## Verification

RED first:

- `.\gradlew.bat test --tests dev.talos.cli.modes.UnifiedAssistantModeTest.staticSelectorRepairFollowUpCarriesCurrentWorkspaceSelectorFacts --no-daemon`

The test failed because the first repair prompt lacked `[Current static selector facts]`.

GREEN:

- `.\gradlew.bat test --tests dev.talos.cli.modes.UnifiedAssistantModeTest.staticSelectorRepairFollowUpCarriesCurrentWorkspaceSelectorFacts --no-daemon`
- `.\gradlew.bat test --tests dev.talos.cli.modes.UnifiedAssistantModeTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.runtime.repair.RepairPolicyTest --tests dev.talos.core.llm.ToolCallRepromptStagePromptDebugTest --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`

Focused audit:

`local/manual-testing/llama-cpp-t216-workspace-aware-selector-facts-re-audit-20260508-041000/FINDINGS-LLAMA-CPP-T216-WORKSPACE-AWARE-SELECTOR-FACTS-RE-AUDIT.md`

Audit result:

- Qwen first repair-turn prompt now includes `[Current static selector facts]`, `Observed in HTML`, `Classes: none`, and the missing selector facts.
- GPT-OSS repair prompt also includes the same selector facts.
- Remaining selector repair failures are no longer explained by missing first-repair-context facts.

## Follow-Up

The next issue is the remaining selector-repair product gap: Talos can identify orphan selector facts deterministically, but still asks the model to invent a coherent repair. A future ticket should consider a deterministic repair assist or stricter target-specific repair policy.
