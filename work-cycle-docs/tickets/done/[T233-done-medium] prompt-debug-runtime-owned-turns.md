# T233 - Prompt Debug Must Distinguish Runtime-Owned Turns From Missing Captures

Status: done
Severity: medium
Closed: 2026-05-08

## Problem

Prompt-debug commands can report that no prompt debug capture exists after a runtime-owned/direct turn, even when previous provider prompt captures exist in the same process. This is misleading during audits.

The correct distinction is:

- no provider prompt was sent for the last turn because Talos answered deterministically,
- captures exist but the last turn has no provider request,
- no captures exist in the process at all.

## Evidence

Audit:

`local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/FINDINGS-LLAMA-CPP-POST-T230-BROAD-PRODUCT-AUDIT.md`

Transcript examples:

- Early runtime-owned/direct turns around `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:157-165`
- Qwen unsupported document/runtime direct answer area around `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:20375-20384`
- GPT analogous early area around `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:157-165`
- GPT unsupported document/runtime direct answer area around `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:22815-22823`

## Scope

- Improve `/prompt-debug last`, `/prompt-debug save`, and `/prompt-debug save-all` messaging for runtime-owned turns.
- If prior captures exist but the last turn did not call a provider, say that explicitly.
- If no captures exist in the process, keep the existing no-capture message.
- Preserve existing prompt-debug artifact format and redaction behavior.

## Acceptance

- Done: added a deterministic runtime-owned/direct answer test followed by `/prompt-debug last`; the message says no provider request was sent for the last turn.
- Done: added a previous-capture plus runtime-owned-last-turn test for `/prompt-debug save-all`.
- Done: preserved the fresh-process no-captures message.
- Done: confirmed protected marker/value search over focused audit prompt-debug artifacts returned no matches.

## Verification

- Test: `.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest.lastExplainsRuntimeOwnedTurnWhenNoProviderPromptWasSent" --no-daemon`
- Test: `.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --no-daemon`
- Broad targeted tests: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon`
- Full verification: `.\gradlew.bat test installDist --no-daemon`
- Focused audit: `local/manual-testing/llama-cpp-t231-t233-focused-audit-20260508-201158/FINDINGS-LLAMA-CPP-T231-T233-FOCUSED-AUDIT.md`

## Non-Goals

- Do not change provider request capture schema.
- Do not include protected data in any prompt-debug output.
- Do not force deterministic runtime-owned turns to create fake provider captures.
