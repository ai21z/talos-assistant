# [T173-done-high] Runtime-Owned Static Web Import Verification Answer

Status: done
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

## Resolution

Talos now recognizes narrow read-only static-web import questions and grounds the answer from the current HTML source instead of trusting model prose. For questions such as:

```text
Which file does index.html import for the BMI script, script.js or scripts.js?
```

the read-only evidence contract requires `index.html`, not the candidate answer files. The final answer is deterministically rendered from current `<script src="...">` imports:

- `scripts.js` imported -> reports `scripts.js`.
- `script.js` imported -> reports `script.js`.
- no matching import -> reports that neither candidate is imported.

Model prose that contradicts this runtime-owned result is replaced before final output.

## Verification

Passed:

```powershell
./gradlew.bat test --tests "*scriptImportInspection*" --tests "*staticWebImportChoiceQuestionTargetsIndexNotCandidateScripts" --tests "*scriptImportQuestionUsesCurrentIndexHtmlAfterExactOverwrite" --no-daemon
./gradlew.bat check --no-daemon
```

## Follow-Up

Focused manual re-audit after the current batch should include:

- Exact `AFTER` overwrite.
- Script import verification for Qwen and GPT-OSS.
