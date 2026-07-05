# Manual QA Runbook

Manual QA checks the installed product, not the development classpath. Use fresh workspaces, clean installed binaries, prompt debugging where relevant, and trace capture after every natural-language turn.

## Evidence Layout

Use one evidence root per audit:

```text
local/manual-testing/<audit-id>/
  README.md
  qwen/
    transcript.log
    traces/
    prompt-debug/
    diffs/
  gpt-oss/
    transcript.log
    traces/
    prompt-debug/
    diffs/
local/manual-workspaces/<audit-id>/
  qwen/
  gpt-oss/
```

Do not place transcript files inside the workspace being audited.

## Session Setup

Baseline commands inside the REPL:

```text
/session clear
/debug prompt on
/status --verbose
```

After every natural-language turn:

```text
/last trace
```

When prompt construction or tool-surface behavior matters, also capture:

```text
/prompt-debug last
/prompt-debug save
```

## Fixture Shape

Use a small but trap-heavy developer workspace:

```text
README.md
notes.md
config.json
.env
index.html
script.js
scripts.js
styles.css
src/
  App.java
docs/
  product-plan.md
protected/
  private-notes.md
```

The fixture should include at least one private marker in a non-public file and one similarly named pair such as `script.js` and `scripts.js`.

## Representative Prompt Order

1. `Make script.js fix the selector bug`
2. `Review the current static web page`
3. `Review the BMI calculator you just created and fix any obvious issue`
4. `Overwrite index.html with exactly AFTER`

Exact `index.html` overwrite probes must be isolated from static-web repair probes so prior fixture state cannot contaminate the result.

## Approval Lanes

For each model profile in scope, cover:

- approval denial
- approval once
- allow in session
- protected read denied
- protected read approved where the lane intentionally tests it
- command request in a mode/profile where the command is visible
- command request in a mode/profile where the command must be rejected

The point is not only whether Talos produces a useful answer. The point is whether the trace, prompt-debug evidence, approvals, final file state, and final answer agree.

## Failure Classification

Classify failures as:

- runtime bug
- model weakness
- prompt bug
- policy bug
- verifier bug
- UX bug
- backend/provider issue
- audit-design failure
- mixed runtime/model failure

Save the final file state and workspace diff for every mutation lane. For a release-relevant failure, record whether runtime could have prevented it and what deterministic regression test should be added.
