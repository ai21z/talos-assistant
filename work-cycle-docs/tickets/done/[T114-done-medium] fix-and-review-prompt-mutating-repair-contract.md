# T114 - Fix-And-Review Prompt Must Resolve To Mutating Repair Contract

Status: done
Severity: medium
Area: task-contracts

## Problem

The prompt `Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.` includes a direct fix request, but the focused Qwen audit classified it as read-only after a failed BMI create.

Evidence:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1505-1506` shows static verification repair context is present.
- The prompt-debug frame for this turn classifies it as `DIAGNOSE_ONLY`, `mutationAllowed: false`, and exposes read-only tools only.

Pure review prompts should stay read-only. Review-plus-fix prompts should allow mutation.

## Scope

- Update task-contract resolution so prompts that ask to review and fix obvious issues resolve to a mutating repair/apply contract.
- Preserve read-only behavior for prompts that ask only to review, inspect, diagnose, or say whether something works.
- Reuse existing repair context when the previous turn failed static verification.

## Acceptance

- Resolver tests cover:
  - `Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.`
  - A pure read-only review prompt.
  - A repair prompt after failed static verification context, if test helpers support it.
- The fix-and-review prompt exposes mutation tools and has a mutating action obligation.
- Pure read-only review still exposes read-only tools only.

## Verification

- Targeted `TaskContractResolver` tests.
- Prompt-debug or executor test for visible tools/action obligation.
- Full `.\gradlew.bat test e2eTest --no-daemon` before closing.

## Completion Notes

- Added direct review-and-fix mutation intent classification for prompts shaped like `review ... and fix ...`.
- Kept pure review/read-only prompts non-mutating.
- Ensured direct review-and-fix current-turn frames expose mutation tools with `MUTATING_TOOL_REQUIRED`.
- Verification passed:
  - `.\gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon`
  - `.\gradlew.bat test e2eTest --no-daemon`
  - `.\gradlew.bat installDist --no-daemon`
