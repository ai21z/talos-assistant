# T135 - Command Profile And Plan Core Types

Severity: high
Status: done

## Problem

Talos needs command execution eventually, but the architecture must start with
typed command facts instead of a generic shell tool. The first slice should add
the records and profile registry needed to describe allowed command shapes
without executing anything.

## Scope

- Add `dev.talos.runtime.command` core records/enums for command profiles,
  command plans, command risk, expected writes, and output limits.
- Add a small `CommandProfileRegistry` with V1 profile definitions.
- Add validation that rejects unknown profiles and invalid cwd/profile input.
- Keep this as data/model policy only: no process execution and no tool
  registration.

## Acceptance

- Command profiles are immutable runtime facts.
- V1 profiles include Gradle verification and read-only Git diagnostics from
  `docs/architecture/10-command-execution-architecture-design.md`.
- Plans contain profile id, executable, argv, cwd, risk, timeout, output caps,
  approval/checkpoint flags, and network/interactive booleans.
- Unknown profiles fail closed.
- No `talos.run_command` tool is exposed.

## Non-Goals

- No `ProcessBuilder`.
- No command execution.
- No shell support.
- No approval or TurnProcessor wiring yet.

## Verification

- Red focused test first failed on missing command core types.
- Focused T135 tests passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.runtime.command.CommandProfileRegistryTest`
- Full verification passed:
  `.\gradlew.bat --no-daemon build installDist`

## Completion Notes

- Added `dev.talos.runtime.command` core records/enums:
  `CommandRisk`, `CommandOutputLimits`, `CommandProfile`, `CommandPlan`, and
  `CommandPlanRejectedException`.
- Added `CommandProfileRegistry` with V1 non-shell profiles for Gradle
  verification, read-only Git diagnostics, Java version, and Talos version.
- Added fail-closed unknown profile and cwd escape behavior.
- No command runner, `ProcessBuilder`, approval wiring, or `talos.run_command`
  tool was added.
