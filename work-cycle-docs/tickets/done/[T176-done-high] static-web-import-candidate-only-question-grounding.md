# [T176-done-high] Static Web Import Candidate-Only Question Grounding

Status: done
Priority: high

## Evidence Summary

- Source: manual managed llama.cpp T61-J full audit
- Date: 2026-05-07
- Branch: `v0.9.0-beta-dev`
- Models/backend:
  - `llama_cpp/qwen2.5-coder-14b`
  - `llama_cpp/gpt-oss-20b`
- Raw transcripts:
  - `local/manual-testing/llama-cpp-t61j-full-audit-20260507-023400/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
  - `local/manual-testing/llama-cpp-t61j-full-audit-20260507-023400/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`
- Findings report:
  - `local/manual-testing/llama-cpp-t61j-full-audit-20260507-023400/FINDINGS-LLAMA-CPP-T61J-FULL-AUDIT.md`

Observed prompt:

```text
Which exact file currently imports the BMI script, script.js or scripts.js? Verify from current files and answer only after inspection. Do not read protected files.
```

Observed behavior:

- Before this prompt, `index.html` had been overwritten to exactly `AFTER`.
- Qwen read `index.html` but answered that `index.html` is the file currently importing the BMI script.
- GPT-OSS read `script.js` and `scripts.js` and answered that BMI logic is in `scripts.js`.
- Neither answer verified the current import relation from the current HTML entry file.
- Both traces showed expected targets as `script.js, scripts.js`.
- The deterministic `[Static web import check]` path did not run.

Concrete evidence:

```text
Qwen:
- transcript lines 15203-15228: answer says index.html imports the BMI script.
- transcript lines 15242-15251: tool read index.html; expected targets are script.js, scripts.js.

GPT-OSS:
- transcript lines 15722-15748: answer says BMI calculation is in scripts.js.
- transcript lines 15762-15772: tools read script.js and scripts.js; expected targets are script.js, scripts.js.
```

Code cross-check:

```text
src/main/java/dev/talos/runtime/verification/StaticWebImportIntent.java
```

`StaticWebImportIntent.matches(...)` currently requires a static web surface token such as `.html`, `html`, `page`, or `web`. The audited wording mentions candidate script files but not the HTML file, so the static import intent does not match.

## Expected Behavior

For candidate-only import questions such as:

```text
Which exact file currently imports the BMI script, script.js or scripts.js?
```

Talos must ground the answer in the current HTML entry file, normally `index.html` when present.

After `index.html` is overwritten to `AFTER`, the correct runtime-owned answer should be equivalent to:

```text
[Static web import check]
Neither `script.js` nor `scripts.js` is currently imported by `index.html`.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `INTENT_CLASSIFICATION`
- `READ_ONLY_EVIDENCE`
- `MODEL_COMPETENCE_CONTAINMENT`

Blocker level:

- release blocker

## Scope

In scope:

- Extend static web import intent recognition to cover candidate-only import questions.
- Treat candidate JS filenames as answer choices, not as the sole evidence targets.
- Select current HTML evidence target(s), with `index.html` as the default entry point when present.
- Keep the final answer runtime-owned for recognized static import checks.
- Preserve existing behavior for prompts that explicitly mention `index.html`.

Out of scope:

- General web crawler behavior.
- Browser execution.
- Broad static analysis of arbitrary bundlers.
- New model prompting policy unrelated to static web import checks.

## Acceptance

- A prompt like `Which exact file currently imports the BMI script, script.js or scripts.js?` triggers the static web import verifier.
- Expected/evidence targets include `index.html` when it exists and no other HTML entry file is more specifically requested.
- Candidate JS files are preserved as candidate answer choices.
- If `index.html` contains only `AFTER`, final output says neither candidate is currently imported.
- Tests cover:
  - explicit `index.html` wording,
  - candidate-only import wording,
  - `script.js` vs `scripts.js` exact-name distinction,
  - no protected-file reads,
  - no regression to static web diagnosis or changed-files summaries.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*StaticWebImport*" --tests "*scriptImport*" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```

Focused manual re-audit after implementation:

- exact `AFTER` overwrite,
- candidate-only import question with Qwen and GPT-OSS,
- confirm `[Static web import check]` appears,
- confirm final answer says neither candidate is imported.

## Resolution

Talos now recognizes candidate-only static import questions such as:

```text
Which exact file currently imports the BMI script, script.js or scripts.js?
```

as static web import checks. Candidate JS paths remain answer choices, while the runtime evidence target becomes the current HTML entry file. `index.html` is selected when present, and the final answer is still runtime-owned through `[Static web import check]`.

## Verification

Passed:

```powershell
./gradlew.bat test --tests "*candidateOnlyStaticWebImportQuestionTargetsIndexNotCandidateScripts" --tests "*scriptImportInspectionGroundsCandidateOnlyQuestionInCurrentIndexHtml" --tests "*candidateOnlyScriptImportQuestionUsesCurrentIndexHtmlAfterExactOverwrite" --tests "*changedFilesUncertaintyQuestionIncludesExplicitRuntimeUncertaintyClause" --no-daemon
./gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check installDist --no-daemon
```
