# T264 - Deictic "What Is In Here" Should Be List-Only
Date: 2026-05-13
Status: Done
Priority: Medium

## Why This Ticket Exists

The broader manual audit showed that a normal user prompt like:

```text
what is in here?
```

can lead Talos to inspect file contents instead of simply listing the workspace. That is too broad for a casual deictic directory question.

## Problem

Talos already classifies explicit listing prompts such as `What files are in this folder?` as `DIRECTORY_LISTING`, which exposes `talos.list_dir` only. The looser phrasing `what is in here?` does not hit that list-only contract and may expose read/search tools.

## Goal

Treat casual deictic "what is in here" prompts as directory listings, while preserving workspace/project explanation behavior for prompts that ask what the project/workspace is.

## Scope

In scope:

- Classify `what is in here?`, `what's in here?`, and close variants as `DIRECTORY_LISTING`.
- Keep `what is this project?` and explanation prompts as workspace explanation.
- Ensure the prompt frame/tool surface remains list-only.
- Add TalosBench coverage with hidden fixture content to prevent content inspection.

Out of scope:

- Broad natural-language classifier rewrite.
- Changing workspace explanation prompts.
- Summarization/content inspection behavior when the user explicitly asks to read/explain contents.

## Acceptance

- `what is in here?` resolves to `DIRECTORY_LISTING`.
- Only `talos.list_dir` is exposed for that turn.
- The model cannot read file contents for that prompt in the benchmark case.
- Hidden fixture content does not leak.
- Existing workspace explanation tests still pass.

## Resolution

Added the conservative deictic listing form `what is/what's in here` to the simple directory-listing classifier. This keeps casual "here" questions list-only without changing project/workspace explanation prompts.

Added TalosBench case `deictic-here-listing-no-content` with hidden fixture content to assert:

- `DIRECTORY_LISTING`
- `talos.list_dir` only
- no `read_file`, `grep`, or `retrieve`
- no hidden token leakage

## Verification

- RED:
  - `.\gradlew.bat test --tests 'dev.talos.runtime.task.TaskContractResolverTest.simpleFolderListingBecomesDirectoryListingContract'`
- GREEN:
  - same focused command passed after implementation.
- Affected validation:
  - `.\gradlew.bat test --tests 'dev.talos.runtime.task.TaskContractResolverTest'`
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest`
- Full verification:
  - `.\gradlew.bat test`
  - `.\gradlew.bat build`
- Installed Talos:
  - `.\gradlew.bat installDist`
  - `pwsh .\tools\install-windows.ps1 -Force -Quiet`
  - `talos -v` reported build `2026-05-13T14:29:24.241653200Z`
- Focused Qwen/GPT-OSS audit:
  - `local/manual-testing/t264-here-listing-audit-20260513-162959/FINDINGS-T264-HERE-LISTING.md`
