# [T183-high] Static Selector Search Scope Truthfulness

Status: done
Priority: high

## Evidence Summary

- Source: managed llama.cpp T61-L full E2E audit
- Date: 2026-05-07
- Branch: `v0.9.0-beta-dev`
- Commit under audit: `d312393`
- Findings report:
  - `local/manual-testing/llama-cpp-t61l-full-e2e-audit-20260507-081444/FINDINGS-LLAMA-CPP-T61L-FULL-E2E-AUDIT.md`

Prompt:

```text
Search for the selector .missing-button using workspace search. Return matching file and line only; do not read full files and do not read protected files.
```

Fixture truth:

- `script.js` contains:
  `const button = document.querySelector('.missing-button');`

Qwen behavior:

- Prompt: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1302`
- Final answer: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1326`
  says no matches were found in HTML files.
- Provider body: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1709`
  shows `include:"*.html"`.

GPT-OSS behavior:

- Prompt: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1378`
- First grep failed with the T180 comma-glob validation:
  `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1418`
- Final answer: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1402`
  says no matches were found in `.html` or `.css` files.
- Provider body: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1835`
  shows the second grep used `include:"*.{html,css}"`.

## Problem

T180 fixed one bad include syntax, but Talos still lets a static selector-search
request complete with a false no-match answer when the model searches only a
scope that excludes JavaScript.

The user asked for workspace search. A no-match answer based only on `.html` or
`.html/.css` evidence is not a valid answer when the fixture's match is in
`script.js`.

## Goal

Static web selector searches must be evidence-scope truthful.

For selector-like patterns such as `.missing-button`, Talos must not allow a
final "no matches" answer unless the effective search scope includes likely
static web source files, especially JavaScript.

## Scope

In scope:

- Detect static web selector-search requests.
- Track the effective `talos.grep` include scope for no-match answers.
- Treat no-match results from scopes such as `*.html`, `*.css`, or
  `*.{html,css}` as incomplete for workspace/static-web selector search.
- Either retry with a broad static-web include such as `*.{html,css,js}`, or
  return an evidence-incomplete/scoped answer that does not pretend the whole
  workspace was searched.
- Preserve T180 behavior: comma-separated include values remain invalid.
- Preserve successful grep results and normal non-selector grep behavior.

Out of scope:

- Full JavaScript AST analysis.
- Browser execution.
- General semantic search.
- Rewriting retrieval.

## Acceptance

- A Qwen-like `talos.grep` call with pattern `.missing-button` and
  `include:"*.html"` does not produce a complete broad no-match answer.
- A GPT-OSS-like sequence with invalid `*.css,*.html`, followed by
  `*.{html,css}`, does not produce a complete broad no-match answer.
- If `script.js` contains `.missing-button`, the final result either finds
  `script.js` or explicitly reports that the evidence is incomplete because
  JavaScript was not searched.
- The final output must not say "no matches in the workspace" when JavaScript
  was excluded.
- Existing valid grep searches still work.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*Grep*" --tests "*StaticWeb*" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```

## Resolution

- Added runtime-owned static selector search grounding for read-only selector
  search turns that used `talos.grep`.
- The grounding searches visible static web files (`.html`, `.htm`, `.css`,
  `.js`, `.ts`, `.jsx`, `.tsx`) and skips hidden/protected-style dotfiles.
- Qwen-like html-only no-match prose is replaced with the actual `script.js`
  file/line match when present.
- GPT-OSS-like invalid comma glob followed by html/css-only no-match prose is
  also replaced with the actual `script.js` file/line match.
- Existing comma-glob validation and grep behavior remain unchanged.

Verification:

```powershell
./gradlew.bat test --tests "*selectorSearchNoMatch*" --no-daemon
./gradlew.bat test --tests dev.talos.tools.impl.GrepToolTest --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```
