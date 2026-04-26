# [done] Ticket: Terminal ASCII/Dumb-Mode Hygiene
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/talos-cli-role-result-rendering-cleanup.md`
Related tickets:
- `work-cycle-docs/tickets/talos-cli-theme-color-capability-foundation.md`
- `work-cycle-docs/tickets/talos-cli-approval-security-ui-polish.md`

## Why This Ticket Exists

Installed transcript capture through a non-interactive PowerShell pipeline
showed terminal corruption:

```text
fi<replacement-char>
changed<replacement-char>
You CAN create files <replacement-char>
File operations ... ?
```

This matters because Talos uses captured transcripts as review evidence. A
local-first CLI should produce readable output in normal terminals, redirected
logs, and dumb terminal paths.

## Problem

Prior UI cleanup removed some visible glyph issues, but non-ASCII punctuation
and symbols remain in user-visible runtime strings and prompt/debug output:

- Unicode ellipsis
- Unicode arrow
- Unicode em dash
- Unicode checkmark
- box drawing or decorative symbols in some docs/render paths

When the terminal is dumb or encoding is not UTF-8 end-to-end, these degrade to
replacement characters or question marks.

## Goal

Make user-visible CLI output and manual transcript capture ASCII-safe when the
terminal/color/capability policy indicates plain or dumb output.

## Scope

### In scope

- Audit user-visible runtime strings for non-ASCII characters.
- Add or reuse a renderer-level ASCII degradation path.
- Ensure dumb terminal / redirected output avoids non-ASCII status glyphs and
  punctuation.
- Add tests for plain/dumb output where feasible.

### Out of scope

- Rewriting documentation comments.
- Removing all Unicode from internal docs or historical local prompt snapshots.
- Full terminal capability rewrite beyond what is needed for evidence hygiene.

## Proposed Work

1. Identify user-visible output paths.

   Likely categories:

   - renderer labels and status lines
   - tool progress summaries
   - verification/failure summaries
   - prompt inspector output
   - prompt system text that can be printed by `/prompt`

2. Centralize degradation.

   Prefer renderer or terminal capability layer over replacing every string
   manually. However, prompt text sent to models may also need ASCII-safe
   source strings because `/prompt` prints it verbatim.

3. Preserve meaning.

   Replace:

```text
   Unicode ellipsis -> ...
   Unicode arrow -> ->
   Unicode em dash -> -
   Unicode checkmark -> OK or [ok]
   Unicode cross mark -> [error]
   Unicode warning sign -> [warning]
```

4. Add regression tests.

   Confirm plain/no-color/dumb rendering contains no replacement characters and
   no non-ASCII control glyphs in key outputs.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/RenderEngine.java`
- `src/main/java/dev/talos/cli/repl/TerminalTheme.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java`
- `src/main/java/dev/talos/core/llm/SystemPromptBuilder.java`
- `src/main/java/dev/talos/core/util/Sanitize.java`
- relevant CLI renderer tests

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.*"
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
```

Manual verification:

- Run installed Talos through a PowerShell pipeline into
  `local/manual-testing/test-output`.
- Check the transcript for replacement characters:

```powershell
Select-String -Path local/manual-testing/test-output -Pattern '<replacement-character-pattern>'
```

## Acceptance Criteria

- Dumb/redirected installed transcript output is readable and contains no
  replacement-character corruption.
- Trusted renderer styling remains semantic in capable terminals.
- No model-facing security/safety behavior changes.
