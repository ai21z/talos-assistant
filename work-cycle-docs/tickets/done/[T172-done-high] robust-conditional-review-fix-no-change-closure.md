# [T172-done-high] Robust Conditional Review/Fix No-Change Closure

Status: done
Priority: high

## Evidence Summary

- Source: manual llama.cpp T61-I full audit
- Date: 2026-05-06
- Branch: v0.9.0-beta-dev
- Models/backends: llama_cpp/qwen2.5-coder-14b, llama_cpp/gpt-oss-20b
- Raw transcript paths:
  - `local/manual-testing/llama-cpp-t61i-full-audit-20260506-222632/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
  - `local/manual-testing/llama-cpp-t61i-full-audit-20260506-222632/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`
- Findings report:
  - `local/manual-testing/llama-cpp-t61i-full-audit-20260506-222632/FINDINGS-LLAMA-CPP-T61I-FULL-AUDIT.md`

Observed behavior:

```text
Both models created or repaired a BMI calculator to static verification success.
The follow-up "review and fix if needed" turn inspected files but did not mutate.
Talos returned:
[Action obligation failed: repair/fix turn inspected files but did not change them.]
```

Expected behavior:

```text
If a conditional review/fix turn inspects relevant static-web files, current static diagnostics have no blocker,
and no mutation is required, Talos should close the turn with a deterministic no-change answer.
```

## Classification

Primary taxonomy bucket:

- `ACTION_OBLIGATION`

Secondary buckets:

- `REPAIR_CONTROL`
- `OUTCOME_TRUTH`

Blocker level:

- release blocker

## Resolution

The bug was in the diagnostics path used by the deterministic no-change closure.
Mutation verification already used target-aware web-file selection for larger fixture folders, but
`ConditionalReviewFixPolicy` called `StaticTaskVerifier.currentWebDiagnostics(...)` without read-path hints.
That made the current-workspace check unavailable in clean audit fixtures that contained README/config/notes/binary
files plus both stale `script.js` and current `scripts.js`.

Resolution:

- Added a target-aware `currentWebDiagnostics(...)` overload.
- Passed `pathsReadThisTurn` from `ConditionalReviewFixPolicy`.
- Preserved linked-asset precedence, so the linked JavaScript file still dominates stale similar siblings.
- Added an audit-shaped regression test with stale `script.js`, current `scripts.js`, and extra fixture files.

## Acceptance Criteria

- A real-model-shaped test reproduces: read `index.html`, read `scripts.js`, reprompt, read another relevant file or no-tool prose, then no mutation.
- If current static diagnostics pass, final output contains a deterministic no-change answer.
- Final output does not contain `repair/fix turn inspected files but did not change them` for the passing no-change path.
- Trace records `CONDITIONAL_REVIEW_FIX` as satisfied by inspection.
- Existing tests for concrete repair claims and static blockers still pass.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Passed:

```powershell
./gradlew.bat test --tests "*conditionalReviewFixAllowsNoChangeWhenPassingWorkspaceHasStaleSimilarScriptSibling" --no-daemon
./gradlew.bat test --tests "*conditionalReviewFix*" --no-daemon
./gradlew.bat test --no-daemon
```

Focused manual re-audit after batch:

- BMI create -> review/fix no-change for Qwen and GPT-OSS.
