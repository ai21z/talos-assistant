# T136 - Command Argument And Risk Policy

Severity: high
Status: open

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

- Focused unit tests for accepted and denied argument shapes.
- `.\gradlew.bat --no-daemon build installDist`.
