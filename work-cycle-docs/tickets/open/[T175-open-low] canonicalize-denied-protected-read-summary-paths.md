# [T175-open-low] Canonicalize Denied Protected-Read Summary Paths

Status: open
Priority: low

## Evidence Summary

- Source: manual llama.cpp T61-I full audit
- Date: 2026-05-06
- Branch: v0.9.0-beta-dev
- Model/backend: llama_cpp/gpt-oss-20b

Observed behavior:

```text
GPT-OSS called the protected path with leading whitespace.
The approval prompt and trace canonicalized `.env`.
The final denial summary rendered `-  .env: approval denied`.
```

Expected behavior:

```text
Denied protected-read summaries should render canonical display paths, matching approval and trace output.
```

## Classification

Primary taxonomy bucket:

- `PERMISSION`

Secondary buckets:

- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

## Architectural Hypothesis

`AssistantTurnExecutor.summarizeDeniedProtectedReadOutcomesIfNeeded(...)` likely renders `ToolOutcome.pathHint()` directly instead of the canonicalized display path.

Likely code areas:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`

## Goal

Make denied protected-read summaries path-canonical and visually clean.

## Non-Goals

- No change to protected-read approval policy.
- No change to trace redaction.
- No change to approved protected-read behavior.

## Acceptance Criteria

- A denied protected read with path `" .env"` renders `- .env: approval denied`.
- It does not render `-  .env`.
- Approved protected reads and trace redaction still behave as before.

## Tests / Evidence

Required:

```powershell
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```
