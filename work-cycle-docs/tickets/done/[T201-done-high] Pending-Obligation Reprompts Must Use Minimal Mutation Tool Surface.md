# T201 - Pending-Obligation Reprompts Must Use Minimal Mutation Tool Surface

Status: done
Severity: high

## Problem

The T200 focused llama.cpp re-audit confirmed T199/T200, but GPT-OSS still failed the static BMI create/repair path under the managed local 8k context window.

The failure was safe but still a product gap. Talos reported a deterministic pending-action-obligation breach and suppressed success prose, but the expected-target retry did not fit in context, so GPT-OSS never got a bounded chance to write the missing `scripts.js` file.

In the failing audit, the retry was only slightly over budget:

- estimated input tokens: `5670`
- budget: `5635`
- context window: `8192`

The pending-obligation reprompt only needs mutation tools that can satisfy the missing target. Passing the full broad tool surface into that reprompt wasted budget and gave the model irrelevant choices.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t200-focused-re-audit-20260507-191758/FINDINGS-LLAMA-CPP-T200-FOCUSED-RE-AUDIT.md`

Transcript evidence:

- First GPT-OSS create failure: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:633-641`
- Remaining target is `scripts.js`: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:636-640`
- Repair path repeats the same shape: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1302-1311`

## Implementation

- `ToolCallRepromptStage` now narrows pending-obligation reprompt tools:
  - expected-target progress: `talos.write_file`, `talos.edit_file`
  - static full-rewrite repair progress: `talos.write_file`
- Normal first-turn mutation surfaces are unchanged.
- Provider forced-tool-choice behavior is preserved where supported.
- Context-budget containment is preserved if even the narrowed reprompt cannot fit.

## Verification

Red tests observed first:

`.\gradlew.bat test --tests dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest --no-daemon`

Focused tests passed:

- `.\gradlew.bat test --tests dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest --no-daemon`

Full verification passed:

- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`

The first parallel run of adjacent Gradle suites hit the known Windows `build/test-results/test/binary/output.bin` cleanup collision; sequential reruns passed.

## Follow-Up

Run a focused Qwen/GPT-OSS re-audit against the T200/T201 static-web workflow. The expected improvement is that GPT-OSS either completes the missing `scripts.js` retry or, if it still fails, the prompt-debug/trace should show a narrowed retry request rather than the broad tool surface.

