# T138 - Run Command V1 Gradle Profiles

Severity: high
Status: done

## Problem

After the command profiles, policies, and runner exist, Talos can expose a
small `talos.run_command` V1 for Gradle verification profiles only.

## Scope

- Add `talos.run_command` for approved V1 profiles.
- Register the tool only after policy and runner gates pass.
- Wire TurnProcessor approval and permission behavior.
- Default all command execution to ask in V1.
- Deny shell/network/destructive/interactive profiles.
- Support Gradle verification profiles first.

## Acceptance

- Gradle verification command asks once, runs, and returns runtime-owned output.
- Approval denial prevents process execution.
- Shell command attempts are denied before approval.
- Timeout/non-zero exit return failure-dominant tool output.
- No Git write operations or package installs are available.

## Non-Goals

- No arbitrary shell.
- No network command profiles.
- No background process manager.

## Verification

- Focused tool, prompt, phase, native-surface, and TurnProcessor tests passed.
- Installed Talos prompt-render smoke exposed `talos.run_command` for verification turns without write/edit tools.
- Installed distribution jar smoke ran one passing and one failing `gradle_test` command through `RunCommandTool`.
- `.\gradlew.bat --no-daemon build installDist` passed.
