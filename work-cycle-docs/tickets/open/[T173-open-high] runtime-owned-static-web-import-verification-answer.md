# [T173-open-high] Runtime-Owned Static Web Import Verification Answer

Status: open
Priority: high

## Evidence Summary

- Source: manual llama.cpp T61-I full audit
- Date: 2026-05-06
- Branch: v0.9.0-beta-dev
- Model/backend: llama_cpp/qwen2.5-coder-14b
- Raw transcript:
  - `local/manual-testing/llama-cpp-t61i-full-audit-20260506-222632/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`

Observed behavior:

```text
After index.html was exactly overwritten to AFTER, Qwen read index.html but answered that
the BMI script is imported from scripts.js.
The actual current index.html contained only AFTER.
```

Expected behavior:

```text
For explicit "which file imports the script, script.js or scripts.js" verification prompts,
Talos should compute the answer from current index.html rather than relying only on model prose.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `VERIFICATION`
- `MODEL_COMPETENCE`

Blocker level:

- release blocker

## Architectural Hypothesis

This is not a prompt construction bug. The runtime required read-only evidence, but still surfaced false model prose.
A narrow deterministic verifier should own this static-web import answer shape.

Likely code areas:

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`

## Goal

When the user asks which exact static web script file is imported, Talos answers from parsed current file content.

## Non-Goals

- No general natural-language verifier.
- No browser execution.
- No broad static web rewrite.

## Acceptance Criteria

- If current `index.html` imports `scripts.js`, answer deterministically that `scripts.js` is imported.
- If current `index.html` imports `script.js`, answer deterministically that `script.js` is imported.
- If current `index.html` has no script import, answer deterministically that neither file is imported.
- Model prose may not contradict the deterministic runtime result.
- Tests reproduce the `AFTER` overwrite case.
- No regressions to static web diagnosis and changed-files summary.

## Tests / Evidence

Required:

```powershell
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```

Focused manual re-audit after batch:

- Exact `AFTER` overwrite -> script import verification for Qwen and GPT-OSS.
