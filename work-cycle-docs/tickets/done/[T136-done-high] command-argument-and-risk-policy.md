# T136 - Command Argument And Risk Policy

Severity: high
Status: done

## Problem

Command execution must reject shell/network/destructive shapes before any
runner exists. Arguments need typed profile-specific validation, not free-form
command strings.

## Scope

- Add `CommandArgumentPolicy`.
- Add `CommandRiskClassifier`.
- Validate path-like args against workspace/cwd rules.
- Deny shell mode, pipelines, redirects, command substitution, destructive
  tokens, background/interactive shapes, network commands, and unknown profile
  args.
- Preserve profile-specific allowlists for Gradle and Git diagnostics.

## Acceptance

- Invalid args produce typed failure reasons.
- Shell strings are denied.
- Network/destructive/interactive shapes are denied.
- Gradle profile args are limited to known safe task/test selectors.
- Git profiles remain read-only.
- No command execution is added.

## Non-Goals

- No process runner.
- No approval UI.
- No `talos.run_command` exposure.

## Verification

- Red focused test first failed on missing `CommandRiskClassifier`.
- Focused T136/T135 tests passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.runtime.command.CommandArgumentPolicyTest --tests dev.talos.runtime.command.CommandProfileRegistryTest`
- Full verification passed:
  `.\gradlew.bat --no-daemon build installDist`

## Completion Notes

- Added `CommandArgumentPolicy` with profile-specific validation.
- Added `CommandRiskClassifier`.
- Routed `CommandProfileRegistry.plan(...)` through argument validation.
- Gradle profiles accept only `--tests`, `--stacktrace`, and `--info` caller
  args.
- Git read-only profiles reject caller args except `git_diff`, which accepts
  workspace-contained pathspecs only.
- Shell syntax, network tokens, destructive tokens, and workspace-escape
  pathspecs fail closed before planning.
- No process runner, approval UI, or `talos.run_command` tool was added.
