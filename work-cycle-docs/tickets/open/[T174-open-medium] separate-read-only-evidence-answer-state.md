# [T174-open-medium] Separate Read-Only Evidence Answer State From Post-Apply Verification

Status: open
Priority: medium

## Evidence Summary

- Source: manual llama.cpp T61-I full audit
- Date: 2026-05-06
- Branch: v0.9.0-beta-dev
- Models/backends: llama_cpp/qwen2.5-coder-14b, llama_cpp/gpt-oss:20b

Observed behavior:

```text
Read-only verify/status prompts emitted:
[Task not verified: verification was required for this turn, but no task verifier ran.]

The warning is confusing for evidence-grounded read-only answers. It is tied to post-apply mutation verification,
not to whether the read-only answer inspected evidence.
```

Expected behavior:

```text
Read-only verify/status turns should distinguish evidence-grounded answers from post-apply task verification.
If evidence was inspected, the output should not use the mutation-oriented "task verifier did not run" warning.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `VERIFICATION`
- `TOOL_SURFACE`

Blocker level:

- candidate follow-up

## Architectural Hypothesis

`ExecutionOutcome` uses one verification-not-run note for both mutation verification and read-only verify/status turns.
This collapses two different concepts.

Likely code areas:

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`

## Goal

Read-only verify/status answers should use an evidence-grounded read-only outcome state, not post-apply mutation verification wording.

## Non-Goals

- No broad outcome model rewrite.
- No weakening of failure/incomplete evidence warnings when no evidence was gathered.

## Acceptance Criteria

- Evidence-grounded read-only verification output does not start with `[Task not verified: ... no task verifier ran.]`.
- Mutation/apply turns that require static verification still preserve the post-apply verification warning when applicable.
- If a read-only verification prompt gathers no evidence and no prior verified evidence exists, output still reports incomplete evidence.

## Tests / Evidence

Required:

```powershell
./gradlew.bat test --tests dev.talos.cli.modes.ExecutionOutcomeTest --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```
