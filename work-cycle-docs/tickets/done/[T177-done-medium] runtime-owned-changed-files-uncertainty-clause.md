# [T177-done-medium] Runtime-Owned Changed-Files Uncertainty Clause

Status: done
Priority: medium

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
State any uncertainty you have about files changed during this audit. Do not claim unverified facts and do not read protected files.
```

Observed behavior:

- Talos returned the runtime-owned changed-files ledger.
- The answer listed verified recorded changes.
- The answer did not explicitly state uncertainty, despite the direct request.
- No tools were called.
- Turn outcome was `TURN_RECORDED`.

Concrete evidence:

```text
Qwen:
- transcript lines 16200-16225: recorded file changes summary only.
- transcript lines 16230-16279: no tools; outcome TURN_RECORDED.

GPT-OSS:
- transcript lines 16795-16820: recorded file changes summary only.
- transcript lines 16825-16875: no tools; outcome TURN_RECORDED.
```

## Expected Behavior

When the user asks for uncertainty about changed files, Talos should keep the deterministic changed-files ledger but add an explicit uncertainty clause.

Example shape:

```text
Recorded file changes in this session/audit:
- ...

Uncertainty:
- This only covers changes recorded by Talos in this session/audit.
- I am not claiming knowledge of protected file contents.
- I am not claiming knowledge of external edits outside the recorded Talos turns.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `UX`
- `RUNTIME_OWNED_SUMMARY`

Blocker level:

- candidate follow-up

## Scope

In scope:

- Detect changed-files questions that explicitly ask for uncertainty.
- Add a concise deterministic `Uncertainty` section to runtime-owned changed-files output.
- Preserve the existing concise output for normal changed-files prompts.
- Do not read protected files.
- Do not imply protected-file knowledge.

Out of scope:

- Full git diff integration.
- File watcher or external-edit detection.
- Replacing the existing mutation ledger.

## Acceptance

- `State any uncertainty you have about files changed during this audit...` includes an explicit uncertainty section.
- Plain `What files changed during this audit?` keeps the concise runtime-owned ledger.
- The uncertainty section distinguishes verified recorded Talos changes from unobserved external edits.
- Protected-file constraints remain intact.
- Trace/outcome remains runtime-owned and deterministic.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*changedFiles*" --tests "*uncertainty*" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```

Focused manual re-audit after implementation:

- changed-files summary prompt,
- uncertainty-specific changed-files prompt,
- protected-read denial regression,
- no accidental protected-file reads.

## Resolution

Runtime-owned changed-files summaries now detect uncertainty-specific prompts and append a deterministic `Uncertainty:` section. Plain changed-files prompts keep the previous concise ledger.

The uncertainty section states that the summary only covers Talos runtime mutation history, does not claim knowledge of external edits, and does not claim knowledge of protected file contents.

## Verification

Passed:

```powershell
./gradlew.bat test --tests "*candidateOnlyStaticWebImportQuestionTargetsIndexNotCandidateScripts" --tests "*scriptImportInspectionGroundsCandidateOnlyQuestionInCurrentIndexHtml" --tests "*candidateOnlyScriptImportQuestionUsesCurrentIndexHtmlAfterExactOverwrite" --tests "*changedFilesUncertaintyQuestionIncludesExplicitRuntimeUncertaintyClause" --no-daemon
./gradlew.bat test --tests dev.talos.runtime.verification.StaticTaskVerifierTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check installDist --no-daemon
```
