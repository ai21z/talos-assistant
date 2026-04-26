# [done] Ticket: Slash Grep Misses CSS Matches
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/work-test-cycle.md`
- `local/tickets/new-work.md`

## Why This Ticket Exists

The installed mode/tool smoke run compared model-invoked `talos.grep` with the
user-facing slash `/grep` command. The tool-path grep found all relevant
matches, while slash `/grep` missed CSS matches.

## Problem

Prompt in chat mode:

```text
Search this workspace for cta-button and tell me where it appears. Do not change anything.
```

Observed model tool result:

```text
The pattern "cta-button" appears in:
- script.js line 2
- style.css lines 12 and 26
```

Then slash command:

```text
/grep cta-button
```

Observed:

```text
Found 1 matches in 1 files:

script.js:
  2: const ctaButton = document.querySelector('.cta-button');
```

Actual `style.css` contains `.cta-button` selectors on lines 12 and 26.

## Goal

Slash `/grep` should search the same workspace surface as `talos.grep`, or
clearly document any intentional difference.

## Scope

### In scope

- Compare slash grep implementation with `talos.grep`.
- Check default include/exclude behavior for CSS files.
- Add tests for `.css`, `.html`, and `.js` matches.

### Out of scope

- Changing retrieval indexing.
- Adding external grep dependencies.

## Proposed Work

1. Inspect slash `GrepCommand` and the underlying grep tool implementation.
2. Ensure default slash grep includes common web text files:

   ```text
   html, css, js, md, txt, json, yaml, java
   ```

3. Add a regression test using a tiny HTML/CSS/JS workspace.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/slash/GrepCommand.java`
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
- `src/test/java/dev/talos/cli/repl/slash/`
- `src/test/java/dev/talos/tools/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "*Grep*"
```

Installed CLI check:

```text
/grep cta-button
```

in `local/playground/horror-synth-site`.

## Acceptance Criteria

- `/grep cta-button` reports both `script.js` and `style.css` matches.
- Tool-path `talos.grep` and slash `/grep` have matching default file coverage
  for common text/web files.
- Any intentional filtering difference is visible in help text.

## Resolution Notes

Updated slash `/grep` default file surface to include CSS-family files
(`css`, `scss`, `sass`, `less`) and added command regression coverage.

Installed CLI retest:

```text
/grep cta-button
Found 3 matches in 2 files:

script.js:
  2:     const ctaButton = document.querySelector('.cta-button');

style.css:
  12: .cta-button {
  26: .cta-button:hover {
```
