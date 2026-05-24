# [T333-done-high] Prompt-Debug Save Absolute Windows Path Mangling

Status: done
Priority: high
Date: 2026-05-24
Branch: `T333`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `4cebece2`

## Scope

T333 fixes the true PTY/JLine path preservation bug found during manual
release-evidence collection.

The failing operator command was:

```text
/prompt-debug save "C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-testing\true-pty-manual-20260520-r1\artifacts\prompt-debug"
```

Talos wrote to a repo-relative mangled directory instead:

```text
C:\Users\arisz\Projects\LOQ\loqj-cli\UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug
```

That made the audit packet incomplete unless the accidental directory was
manually noticed and scanned.

## Root Cause

The bug was not in `PromptDebugCommand.promptDebugDirectory(...)` itself. Direct
command execution with quoted or unquoted absolute destinations already resolves
properly.

The corruption happened before the slash command saw the argument. JLine's
`LineReaderImpl.finish(...)` removes characters treated as parser escape
characters while event expansion is enabled. JLine's default parser treats
backslash as an escape character, so a literal Windows path like:

```text
C:\Users\arisz\Projects\LOQ\loqj-cli
```

could arrive at Talos as:

```text
C:UsersariszProjectsLOQloqj-cli
```

On Windows, that drive-relative string normalizes under the current working
directory, producing the observed repo-relative `Usersarisz...` artifact
directory.

## What Changed

Updated:

```text
src/main/java/dev/talos/cli/launcher/RunCmd.java
src/test/java/dev/talos/cli/launcher/RunCmdTerminalModeTest.java
src/test/java/dev/talos/cli/repl/slash/PromptDebugCommandTest.java
```

`RunCmd` now disables JLine event expansion in the shared LineReader builder:

```text
LineReader.Option.DISABLE_EVENT_EXPANSION = true
```

This preserves literal backslashes in true terminal input before slash-command
routing.

Additional command-level tests prove:

- `/prompt-debug save <absolute-dir>` writes under the requested destination;
- `/prompt-debug save "<absolute-dir>"` writes under the requested destination;
- saved Markdown and provider-body JSON follow the same destination.

## Behavior Preservation

T333 does not change:

- prompt-debug redaction policy;
- prompt-debug default destination precedence;
- `~/.talos/prompt-debug` default behavior;
- `save-all` semantics;
- prompt-debug provider-body JSON formatting;
- slash-command routing;
- approval handling;
- prompt rendering;
- terminal/system-terminal selection.

The only runtime behavior change is that JLine no longer strips backslashes
from accepted input lines through event expansion.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --no-daemon
```

Expected failure occurred before implementation:

```text
expected: </prompt-debug save "C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-testing\example\artifacts\prompt-debug">
but was: </prompt-debug save "C:UsersariszProjectsLOQloqj-clilocalmanual-testingexampleartifactsprompt-debug">
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --no-daemon
```

The focused terminal regression passed after disabling JLine event expansion.

Command-level destination coverage also passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --no-daemon
```

## Rejected Scope

T333 deliberately did not:

- change prompt-debug artifact naming;
- move prompt-debug ownership;
- alter prompt-debug redaction;
- add broad Windows path normalization rules;
- reinterpret malformed drive-relative paths after JLine has already corrupted
  them;
- run or rewrite manual audit packets.

The correct fix is to preserve the user's input before the slash command sees
it, not to guess a damaged path later.

## Verification

Focused verification run during implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --no-daemon
```

Results:

- RED terminal regression failed before implementation with backslashes stripped.
- GREEN terminal regression passed after implementation.
- Prompt-debug command destination tests passed.
- Combined focused launcher and prompt-debug command test run passed.

Final gate for this branch:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Results:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `git diff --check`: passed with line-ending warnings only for touched Java
  files.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 8 executed, 6 up-to-date).

## Next Move

After T333 integrates, resume the outcome-truthfulness lane from fresh
`origin/v0.9.0-beta-dev`.

The next selected implementation ticket before this release-evidence fix was:

```text
T403: inspect post-T402 ExecutionOutcome shape before choosing the next
runtime outcome ownership slice.
```
