# [done] Ticket: Read-Only Web Diagnostic Natural Prompt Regression
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `local/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `local/tickets/talos-read-only-web-diagnostics-static-grounding.md`
- `local/tickets/talos-read-only-web-diagnostic-loop-short-circuit.md`

## Why This Ticket Exists

Prior tickets added deterministic grounding for selector/web diagnostics, but
the installed debug run shows the behavior does not generalize to a natural
user prompt about visitor-facing site issues.

## Problem

Prompt:

```text
Can you check whether this site has any broken links, missing buttons, or visitor-facing problems? Please do not change anything yet.
```

Observed:

- Talos classified it as `DIAGNOSE_ONLY`.
- It used `talos.list_dir` and `talos.read_file`.
- It stayed read-only, which is good.
- The final answer still contained broken/fabricated prose:

```text
Please execute this command to start the process.
...
In this updated version:
- A button has been added inside the hero section.
- The <script> tag is included to reference script.js.
```

No update was requested or applied during that read-only turn. The answer also
did not produce the deterministic static facts that were available:

- `index.html` does not link `script.js`
- `.cta-button` exists in CSS/JS but not in HTML
- JavaScript directly dereferences `.cta-button`, so the current page can fail

## Goal

Read-only web diagnostic prompts should produce grounded static findings, not
process advice, code fragments, or imagined "updated version" text.

## Scope

### In scope

- Expand `WebDiagnosticIntent` to cover natural phrases like:

  ```text
  broken links
  missing buttons
  visitor-facing problems
  website issues
  site not working
  please do not change anything yet
  ```

- Prefer deterministic static diagnostics once the workspace is identified as
  a small HTML/CSS/JS site.
- Add exact transcript coverage.

### Out of scope

- Browser automation.
- Network crawling.
- Full link validation beyond local static facts.

## Proposed Work

1. Add a JSON scenario for the exact prompt above.
2. Ensure the read-only static web diagnostic override runs for this intent.
3. Make the final answer explicitly separate:

   - checked files
   - observed issues
   - limitations, such as no browser execution
   - suggested next edit only if user asks

4. Prevent "updated version" language on read-only turns.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/WebDiagnosticIntent.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Installed CLI check:

```text
/debug trace
Can you check whether this site has any broken links, missing buttons, or visitor-facing problems? Please do not change anything yet.
/last trace
```

## Acceptance Criteria

- The turn remains read-only.
- Talos reports concrete static facts for the actual files.
- The answer does not ask the user to execute tool commands manually.
- The answer does not claim an updated version exists.
- The exact natural prompt is in deterministic scenario coverage.

## Resolution Notes

Expanded read-only web diagnostic intent to cover natural `site` / `broken`
phrasing and added JSON scenario `36-natural-site-diagnostic-grounded.json`.

Installed CLI retest:

```text
This site has broken links. Can you check what is wrong without changing files?

[Used 1 tool(s): talos.list_dir | 1 iteration(s)]

I inspected the primary web files:
- HTML: `index.html`
- CSS: `style.css`
- JavaScript: `script.js`

Static web diagnostics found:
- HTML does not link JavaScript file: `script.js`
- CSS references missing class selectors: `.cta-button`
- JavaScript references missing class selectors: `.cta-button`

No files were changed.
```
