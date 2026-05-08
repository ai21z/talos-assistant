# T231 - Conditional Review/Fix Must Not Complete After Failed Mutation Without Evidence

Status: done
Severity: high
Closed: 2026-05-08

## Problem

A conditional review/fix turn can end as successful "No file change is required" even after the model attempted a failed mutation against a nonexistent file and did not inspect the relevant files.

This is a correctness bug. A no-change answer is only valid when Talos has evidence that the current workspace was inspected and no browser-blocking issue exists. A failed edit to a wrong or nonexistent file is not evidence of no-change.

## Evidence

Audit:

`local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/FINDINGS-LLAMA-CPP-POST-T230-BROAD-PRODUCT-AUDIT.md`

Qwen transcript:

- Prompt and frame: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:17125-17138`
- Failed edit and false no-change answer: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:17149-17151`
- Trace tool list and false preview: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:17164-17172`
- Trace outcome/action obligation: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:17203-17209`

Passing comparison:

- GPT-OSS read `index.html`, `scripts.js`, and `styles.css`, then Talos produced a runtime-owned no-change answer with static no-blocker evidence.
- See `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:19324-19413`.

Likely code surfaces:

- `src/main/java/dev/talos/runtime/policy/ConditionalReviewFixPolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/policy/ResponseObligationVerifier.java`

## Scope

- A conditional review/fix turn may complete as no-change only when Talos has successful relevant inspection evidence and runtime static diagnostics confirm no current blocker.
- If any mutating tool call fails during the conditional review/fix turn and there is no later successful, relevant inspection/static no-blocker evidence, the turn must become failure-dominant or a typed obligation breach.
- The final answer must not include successful no-change prose after a failed wrong-target mutation.
- Preserve the passing GPT-OSS shape: successful reads of relevant files plus static no-blocker diagnostics should still produce the deterministic runtime no-change answer.

## Acceptance

- Done: added a focused scripted executor test where the model:
  - receives a conditional review/fix request,
  - lists the root directory,
  - attempts `talos.edit_file` on nonexistent `bmi_calculator.js`,
  - then returns `No file change is required`.
- Done: asserted the outcome is not successful completion.
- Done: asserted the final answer is failure-dominant and mentions the failed target.
- Done: preserved successful no-change when inspection evidence and runtime static diagnostics pass.
- Done: asserted no success/no-change prose is emitted after the failed-mutation case.

## Verification

- Test: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*conditionalReviewFixFailsAfterRetryMutatingToolTargetsMissingFile" --no-daemon`
- Test: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*conditionalReviewFix*" --no-daemon`
- Test: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*invalidMutationRetryAfterReadOnlyToolLoopFailsOutcome" --no-daemon`
- Broad targeted tests: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon`
- Full verification: `.\gradlew.bat test installDist --no-daemon`
- Focused audit: `local/manual-testing/llama-cpp-t231-t233-focused-audit-20260508-201158/FINDINGS-LLAMA-CPP-T231-T233-FOCUSED-AUDIT.md`

## Non-Goals

- Do not rewrite the broad conditional review/fix prompting.
- Do not add a planner.
- Do not weaken the happy path where real inspection evidence proves no file change is needed.
