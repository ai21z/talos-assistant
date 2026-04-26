# [open] Ticket: CLI Help And Tools Output Discoverability Regression
Date: 2026-04-26
Priority: medium
Status: open
Architecture references:
- `docs/new-architecture/30-cli-ui-output-architecture-audit.md`
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/talos-cli-layered-help.md`
- `work-cycle-docs/tickets/talos-terminal-ascii-dumb-mode-hygiene.md`

## Why This Ticket Exists

Installed CLI evidence should be readable and useful for normal users. The
0.9.3 manual transcript shows two regressions in the first commands users run:
`/help all` and `/tools`.

## Problem

Manual transcript:

```text
/help all

/mode <mode>            Switch active mode. Available: auto, rag, c...
/explain-last-turn [opts] Inspect the latest turn from structured aud...
```

The truncation hides important mode names and debug command purpose.

Manual transcript:

```text
/tools

edit_file write Replace a unique string in a workspace file. TIP: call
talos.read_file first to see the exact content. old_string must match the file
exactly ? strip any line-number prefixes from read_file output before using.
```

The source currently contains a Unicode em dash in `FileEditTool.java`'s
user-visible description, and this transcript path rendered that punctuation
as `?`:

```java
old_string must match the file exactly - strip any line-number prefixes...
```

In source this is currently a Unicode dash, which is not safe in plain
transcript paths.

## Goal

Make `/help all` and `/tools` readable in installed PowerShell sessions and
manual transcript capture.

## Scope

### In scope

- Preserve critical summaries in `/help all`.
- Avoid non-ASCII punctuation in tool descriptions or degrade it centrally
  before terminal output.
- Add focused CLI output tests.

### Out of scope

- Redesigning the whole help system.
- Adding new slash commands.
- Changing model/tool policy.

## Proposed Work

1. Replace or centrally degrade the Unicode dash in `FileEditTool` user-visible
   descriptions.
2. Revisit `HelpCommand.listSummary()`:

   - avoid truncating the mode list into `auto, rag, c...`
   - prefer command-specific concise summaries where needed
   - consider wrapping detail in `/help <cmd>` while keeping `/help all`
     understandable

3. Add installed-style plain-output tests for:

   - `/help all`
   - `/tools`
   - no replacement question marks in known tool descriptions

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/ModeCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/ToolsCommand.java`
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`
- `src/main/java/dev/talos/cli/ui/AnsiColor.java`
- `src/test/java/dev/talos/cli/repl/slash/SimpleCommandsTest.java`
- `src/test/java/dev/talos/cli/repl/slash/ToolsCommandTest.java`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.SimpleCommandsTest"
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ToolsCommandTest"
```

Installed CLI manual check:

```text
/help all
/help mode
/help explain-last-turn
/tools
```

## Acceptance Criteria

- `/help all` does not hide the available mode list behind `c...`.
- `/help all` keeps debug command summaries understandable.
- `/tools` contains no replacement `?` caused by Unicode punctuation.
- The transcript remains readable in normal PowerShell and redirected output.
