# [T175-done-low] Canonicalize Denied Protected-Read Summary Paths

Status: done
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

## Resolution

Denied protected-read summaries now render a canonical display path by trimming model-supplied path whitespace and normalizing backslashes before formatting the final denial list.

The audited shape:

```text
pathHint = " .env"
```

now renders:

```text
- .env: approval denied
```

and not:

```text
-  .env: approval denied
```

Protected-read approval policy, trace redaction, and approved protected-read behavior were not changed.

## Verification

Passed:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*deniedProtectedReadSummaryCanonicalizesDisplayPath" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```
