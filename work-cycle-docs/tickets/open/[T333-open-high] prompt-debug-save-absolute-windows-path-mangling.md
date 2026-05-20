# T333 - Prompt-Debug Save Absolute Windows Path Mangling

Status: open - true PTY audit found a prompt-debug artifact path bug
Severity: high / audit-evidence integrity
Release gate: yes for clean release-evidence packets
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-20
Owner: unassigned

## Problem

During the completed true PTY/JLine audit, the operator ran:

```text
/prompt-debug save "C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-testing\true-pty-manual-20260520-r1\artifacts\prompt-debug"
```

Talos reported that prompt-debug files were saved to:

```text
C:\Users\arisz\Projects\LOQ\loqj-cli\UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug
```

The explicit absolute Windows path was mangled into a repo-relative directory.

## Evidence

Audit packet:

```text
local/manual-testing/true-pty-manual-20260520-r1/artifacts
```

Observed saved prompt-debug files:

```text
UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug/prompt-debug-20260520-234842.md
UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug/prompt-debug-20260520-234842.provider-body.json
```

The artifact canary scan included this actual output directory and passed:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-testing\true-pty-manual-20260520-r1\artifacts,C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\true-pty-manual-20260520-r1\workspace,C:\Users\arisz\Projects\LOQ\loqj-cli\UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug" "-PartifactScanAllowlist=C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\true-pty-manual-20260520-r1\workspace\.env" --no-daemon
```

Result: PASS.

## User Impact

Maintainers can believe prompt-debug files were saved into the intended audit
artifact directory while they were actually written elsewhere. That can cause
incomplete artifact scans and stale or missing evidence in release packets.

## Product Risk

This is not a privacy leak in the observed run, but it is an audit-integrity bug.
The prompt-debug files were safe only because the actual mangled directory was
noticed and scanned manually.

## Runtime Boundary Affected

Slash command parsing, quoted Windows absolute paths, prompt-debug artifact
destination handling, audit evidence completeness.

## Required Behavior

- `/prompt-debug save "<absolute Windows path>"` must preserve the drive,
  separators, and target directory.
- The saved Markdown/provider-body JSON should be created under the requested
  directory.
- Quoted and unquoted absolute Windows paths should be covered by tests.
- Existing default `~/.talos/prompt-debug` behavior must remain unchanged.

## Proposed Test

Add slash-command regression coverage that invokes:

```text
/prompt-debug save "C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-testing\example\artifacts\prompt-debug"
```

Expected result:

- output path starts with the requested absolute directory;
- no repo-relative `UsersariszProjects...` directory is created;
- provider-body JSON, when present, follows the same destination.

## Related Files

- `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java`
- `src/test/java/dev/talos/cli/repl/slash/PromptDebugCommandTest.java`
- `work-cycle-docs/reports/lane-labeled-two-model-prompt-bank-audit-20260520.md`
