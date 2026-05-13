# T260 - Natural List Prompts Must Not Be Swallowed By DevMode
Date: 2026-05-13
Status: Done
Priority: High

## Why This Ticket Exists

The T252-T258 focused re-audit used normal verification prompts:

```text
List names only at workspace root. Does ideas exist here? Answer from evidence only.
List names only for batch-one and workspace root. Did batch-two and batch-one/styles-copy.css get created? Answer from evidence only.
```

Both prompts were intercepted before the assistant/tool path and returned:

```text
i Not found: names
```

No `talos.list_dir` call happened and no model/tool turn was sent.

Evidence:

- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 288-305 and 2570-2588.
- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 430-448 and 3387-3405.
- `src/main/java/dev/talos/cli/modes/DevMode.java` lines 29-45 and 119-132.

## Problem

`DevMode.canHandle(...)` accepts any prompt starting with `list `. `DevMode.extractPathArg(...)` then treats the second word as a path. In `List names only...`, the word `names` becomes a bogus path, producing `Not found: names`.

This is a command-routing bug, not a model behavior problem.

## Goal

Natural-language list questions should go through the assistant/tool path unless they are clearly structural DevMode commands.

## Scope

In scope:

- Narrow `DevMode.canHandle(...)` and/or path extraction so `list names only...` is not treated as `list <path>`.
- Preserve structural commands:
  - `list`
  - `list .`
  - `list src`
  - `ls src`
  - `dir build`
  - `list the files here`
- Route evidence-style natural prompts to the assistant/tool path where `talos.list_dir` can be used.

Out of scope:

- Removing DevMode.
- Rewriting all prompt routing.
- Changing slash commands.

## Acceptance

- `List names only at workspace root. Does ideas exist here? Answer from evidence only.` does not return `Not found: names`.
- The prompt routes to assistant/tool handling or a deterministic directory-list evidence path.
- Existing DevMode command tests still pass.
- New regression tests cover both uppercase and lowercase `list names only...`.

## Required Verification

- `DevModeTest` and `PromptClassifierTest` updates.
- A focused scripted REPL/e2e probe confirming a natural list evidence question is not swallowed.
- Include this probe in the next focused two-model audit.

## Implementation Evidence

Implemented on branch `codex/model-setup-flow`.

Tests added/updated:

- `src/test/java/dev/talos/cli/modes/DevModeTest.java`
- `src/test/java/dev/talos/cli/modes/PromptClassifierTest.java`
- `src/test/java/dev/talos/cli/modes/ModeControllerTest.java`

Verification run:

```text
.\gradlew test --tests dev.talos.cli.modes.DevModeTest --tests dev.talos.cli.modes.PromptClassifierTest --tests dev.talos.cli.modes.ModeControllerTest
BUILD SUCCESSFUL

.\gradlew test
BUILD SUCCESSFUL

.\gradlew installDist
BUILD SUCCESSFUL

.\gradlew build
BUILD SUCCESSFUL
```

Scripted route smoke:

```text
/route List names only at workspace root. Does ideas exist here? Answer from evidence only.
Route: ASSIST
Trigger: action intent (tool-calling)
Steps include: no dev command match
```

## Resolution

The focused T254 two-model audit confirmed that natural evidence/list prompts
now route through the assistant/tool path instead of DevMode path extraction.

Audit prompt:

```text
list names only at workspace root. did brief.txt, index.html, styles.css, scripts.js, or script.js get created or changed? answer from evidence only.
```

Result:

- Qwen reached prompt-debug/tool handling with `WORKSPACE_EXPLAIN` and
  `talos.read_file`; it did not return `Not found: names`.
- GPT-OSS reached prompt-debug/tool handling with `WORKSPACE_EXPLAIN` and
  read-only tools; it did not return `Not found: names`.
- The remaining quality variance in the model answer is evidence-summary
  behavior, not DevMode swallowing.

## Closure Verification

- `.\gradlew.bat test --tests 'dev.talos.cli.modes.DevModeTest' --tests 'dev.talos.cli.modes.PromptClassifierTest' --tests 'dev.talos.cli.modes.ModeControllerTest'`
- Focused two-model audit:
  `local/manual-testing/t254-source-target-splitting-audit-20260513-153310/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
- Focused two-model audit:
  `local/manual-testing/t254-source-target-splitting-audit-20260513-153310/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`
