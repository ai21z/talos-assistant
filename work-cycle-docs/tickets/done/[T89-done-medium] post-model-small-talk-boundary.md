# T89 - Small Talk After Slash/Model Command Remains Direct-Answer Only

Status: Done
Priority: Medium
Branch: v0.9.0-beta-dev

## Source

- T61-C milestone QA summary: `local/manual-testing/t61-c-milestone-qa-20260502-155141/SUMMARY-T61-C.md`
- T61-C findings: `local/manual-testing/t61-c-milestone-qa-20260502-155141/FINDINGS-T61-C.md`
- Full run trace: `trc-76217bd6-c4d8-49ac-8762-6cc26d01cc97`
- Failed prompt: `Hello friend, how are you after the model command?`

## Problem

T67 fixed the plain post-model small-talk prompt `Hello friend, how are you?`, but T61-C found that the natural variant `Hello friend, how are you after the model command?` still classified as `READ_ONLY_QA` and exposed read-only workspace tools. No tools were called and no data leaked, but the current-turn contract was wrong.

## Implementation

- Added a conversation-boundary pattern for friendly `hello`/`hi`/`hey` prompts containing `how are you`.
- Kept the existing workspace and mutation vetoes ahead of the friendly-chat pattern, so real workspace intent still wins.
- Added task-contract and executor prompt-audit coverage for the exact T61-C wording.
- Added a manual-gated TalosBench case `t89-post-model-command-small-talk` using the exact failed prompt.

## Acceptance Evidence

- `Hello friend, how are you after the model command?` now classifies as `SMALL_TALK`.
- Prompt audit shows `DIRECT_ANSWER_ONLY`, no native tools, no prompt tools.
- Workspace-intent greetings still stay outside direct chat, including `Hello friend, read notes.md`, `how are you and can you inspect this repo?`, and `Hello friend, how are you after reading README.md?`.
- `/model` and the existing T67 case remain covered; T89 adds the exact T61-C variant as a sibling case.

## Verification

- Red test before implementation:
  - `.\gradlew.bat test --tests dev.talos.runtime.policy.ConversationBoundaryPolicyTest.postModelCommandGreetingIsDirectAnswerOnly --no-daemon` failed with `expected: <DIRECT_CHAT> but was: <NONE>`.
- Targeted tests after implementation:
  - `.\gradlew.bat test --tests dev.talos.runtime.policy.ConversationBoundaryPolicyTest.postModelCommandGreetingIsDirectAnswerOnly --no-daemon` - passed.
  - `.\gradlew.bat test --tests dev.talos.runtime.policy.ConversationBoundaryPolicyTest --tests dev.talos.runtime.task.TaskContractResolverTest.conversationBoundaryPromptsBecomeSmallTalkContracts --tests dev.talos.runtime.task.TaskContractResolverTest.workspaceIntentBoundaryPromptsAreNotSmallTalkContracts --tests dev.talos.cli.modes.AssistantTurnExecutorTest.modelSwitchStyleSmallTalkDoesNotExposeToolsOrExpiredContextInPromptAudit --no-daemon` - passed.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - validated 31 cases.
- `.\gradlew.bat test --no-daemon` - passed.
- `.\gradlew.bat installDist --no-daemon` - passed.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat` - completed. Summary: `local/manual-testing/talosbench/20260502-182243/summary.md`; automated cases passed and approval-sensitive/manual cases remained `MANUAL_REQUIRED`.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId t89-post-model-command-small-talk -IncludeManualRequired` - passed. Summary: `local/manual-testing/talosbench/20260502-182609/summary.md`.
- `.\gradlew.bat e2eTest --no-daemon` - passed.

## Residual Risk

The pattern intentionally covers friendly status greetings, not all prompts mentioning slash or model commands. Real model-help questions and workspace/file instructions remain outside this ticket unless they separately meet existing direct-answer policies.
