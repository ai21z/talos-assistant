# T232 - Directory Listing Must Preserve Requested Target Evidence

Status: done
Severity: medium-high
Closed: 2026-05-08

## Problem

For a directory-listing request, Talos can render the answer from the latest successful `talos.list_dir` result instead of the requested target's result. If the model over-calls `talos.list_dir`, an unrelated empty subdirectory can overwrite the correct root listing in the final answer.

The user sees a false answer even though correct evidence existed earlier in the same turn.

## Evidence

Audit:

`local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/FINDINGS-LLAMA-CPP-POST-T230-BROAD-PRODUCT-AUDIT.md`

Qwen failing transcript:

- Prompt: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3238`
- Tool summary: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3262`
- Trace shows `talos.list_dir -> . [ok]`, then empty subdirectories, then failed file-path listings: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3279-3292`
- False assistant preview: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3296`

GPT-OSS passing comparison:

- Prompt: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:3344`
- Single correct root listing: `local/manual-testing/llama-cpp-post-t230-broad-product-audit-20260508-175200/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:3394-3397`

Likely code surfaces:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:2173-2193`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java:1335-1358`

Both paths currently render from the latest successful `talos.list_dir` body rather than a target-aware selection.

## Scope

- Make directory-listing final answers target-aware.
- For "this folder" or unnamed directory-listing requests, prefer the successful listing for `.`.
- For a named directory request, prefer the successful listing for that named path.
- Do not let later successful listings of unrelated directories replace the requested target's evidence.
- Do not let failed file-path `list_dir` calls produce a false empty answer.
- Preserve the no-content behavior: directory-listing responses must not read or summarize file contents.

## Acceptance

- Done: added a test where a scripted model calls:
  - `talos.list_dir` on `.`,
  - `talos.list_dir` on an empty subdirectory,
  - `talos.list_dir` on one or more file paths that fail.
- Done: asserted the final answer lists the requested root entries, not the empty subdirectory.
- Done: added a named empty subdirectory test and preserved the valid empty answer.
- Done: asserted `README.md` and `notes.md` contents are not read or quoted for list-only prompts.
- Done: preserved trace visibility for model over-calls and invalid file-path `list_dir` attempts.

## Verification

- Test: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*directoryListingUsesRequestedRootEvenWhenModelListsEmptySubdirectories" --no-daemon`
- Test: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*directoryListingUsesExplicitNamedDirectoryWhenUserRequestedIt" --no-daemon`
- Test: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*directoryListing*" --no-daemon`
- Test: `.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.directoryListingStopsAfterSuccessfulListDir" --no-daemon`
- Broad targeted tests: `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon`
- Full verification: `.\gradlew.bat test installDist --no-daemon`
- Focused audit: `local/manual-testing/llama-cpp-t231-t233-focused-audit-20260508-201158/FINDINGS-LLAMA-CPP-T231-T233-FOCUSED-AUDIT.md`

## Non-Goals

- Do not change the general list_dir tool semantics.
- Do not add file-content inspection to directory-listing turns.
- Do not suppress trace visibility of the model's extra failed calls.
