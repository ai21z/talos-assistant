# [T180-done-medium] Grep Include Multi-Glob Guard

Status: done
Priority: medium

## Evidence Summary

- Source: managed llama.cpp T61-K full E2E audit
- Date: 2026-05-07
- Branch: `v0.9.0-beta-dev`
- Commit under audit: `417ab98`
- Findings report:
  - `local/manual-testing/llama-cpp-t61k-full-e2e-audit-20260507-071629/FINDINGS-LLAMA-CPP-T61K-FULL-E2E-AUDIT.md`

Observed prompt:

```text
Search for the selector .missing-button using workspace search. Return matching file and line only; do not read full files and do not read protected files.
```

Observed behavior:

- Qwen called `talos.grep`.
- The call used `include: "*.html, *.css"`.
- The tool returned no matches.
- The fixture contained `.missing-button` in `script.js`.

Concrete evidence:

- Prompt: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1290`
- False negative answer: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1314`
- Tool arguments: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1694`

Tool call:

```json
{"pattern":"\\.missing-button","include":"*.html, *.css","max_results":10,"regex":"true"}
```

## Problem

`talos.grep` accepted a comma-separated include value as if it were a valid
single include pattern. That produced a silent false negative.

The model made a bad argument choice, but the tool should not make the result
look like a reliable workspace search.

## Goal

Make bad include syntax visible and prevent silent false negatives.

## Scope

In scope:

- Detect comma-separated include values such as `*.html, *.css`.
- Either reject them with a clear diagnostic or intentionally support multiple include globs.
- Ensure selector searches do not accidentally exclude JavaScript fixtures without a visible warning.
- Keep protected files excluded.

Out of scope:

- Replacing grep with a full code-search engine.
- Reading full files for search-only prompts.
- Changing retrieval indexing.

## Acceptance

- `include: "*.html, *.css"` does not silently return "no matches".
- If multi-glob support is added, each glob is applied deliberately and documented in tool output.
- If validation rejection is chosen, the tool result names the bad include argument and asks for a valid single glob or supported list shape.
- Tests cover:
  - comma-separated include string,
  - selector match in `script.js`,
  - protected-file exclusion,
  - normal single-glob behavior.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*Grep*" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```

## Resolution

`talos.grep` now treats comma-separated top-level `include` values as invalid
parameters instead of passing them to the Java glob matcher and silently
returning false negatives.

The tool still accepts one normal glob such as `*.js`, and it preserves Java
glob brace alternatives such as `*.{html,css,js}` because commas inside braces
are not treated as top-level separators.

## Verification

Passed:

```powershell
./gradlew.bat test --tests dev.talos.tools.impl.GrepToolTest --no-daemon
./gradlew.bat check installDist --no-daemon
```
