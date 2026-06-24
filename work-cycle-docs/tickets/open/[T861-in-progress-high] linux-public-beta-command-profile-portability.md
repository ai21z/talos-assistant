# [T861-in-progress-high] Linux Public Beta Command Profile Portability

Status: in-progress
Priority: high

## Evidence Summary

- Source: static code and docs review
- Date: 2026-06-23
- Talos version / commit: `0.10.5` / `723d4cd2`
- Branch: `v0.9.0-beta-dev`
- Model/backend: not applicable
- Workspace fixture: not applicable
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: platform-aware Gradle profile executable resolution,
  selected-wrapper pre-approval validation, POSIX environment allowlist,
  Linux command-portability CI smoke lane, and public install/support docs
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: focused Windows command tests passed locally; command
  tests were made host-neutral for the Linux lane; site static/build checks
  passed; full `check` passed; Linux CI lane now includes a stripped-env Gradle
  smoke but has not yet been observed remotely in this worktree

Redacted prompt sequence:

```text
Static product-readiness review asked whether Linux must be supported before beta.
```

Expected behavior:

```text
Talos can honestly advertise a Windows + Linux public beta only if the runtime
command-profile path and install/distribution path work on Linux, with evidence
from source tests and at least one Linux smoke lane.
```

Observed behavior:

```text
Built-in Gradle command profiles hardcode `.\\gradlew.bat`, while the planner
only checks that either `gradlew.bat` or `gradlew` exists. The process-runner
environment allowlist is Windows-shaped and omits POSIX basics such as HOME,
LANG, LC_ALL, and TMPDIR. Public installation docs still say Windows x64 only.
```

Current implementation state:

```text
Built-in Gradle command profiles now resolve through an injected
CommandRuntimePlatform seam: Windows uses `.\\gradlew.bat`, POSIX uses
`./gradlew`. The Gradle wrapper pre-approval guard checks the selected wrapper,
not merely the existence of either wrapper. ProcessCommandRunner filters command
environment variables through the same platform seam. Public docs now split the
Windows packaged lane from the Linux source/developer beta lane.
```

## Classification

Primary taxonomy bucket:

- `COMMAND_PROFILE`

Secondary buckets:

- `PLATFORM_PORTABILITY`
- `RELEASE_GATE`
- `VERIFICATION`

Blocker level:

- release blocker

Why this level:

```text
Linux is required for a credible public beta aimed at local-model and developer
audiences. A Linux user can currently reach a command profile that plans around
a POSIX wrapper but executes the Windows wrapper path.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Just document Linux as unsupported.
```

Architectural hypothesis:

```text
Command profile execution needs an OS-aware executable resolution boundary, and
the public beta install promise needs a Linux lane that matches what the runtime
actually supports.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/command/CommandProfileRegistry.java`
- `src/main/java/dev/talos/runtime/command/CommandToolPlanner.java`
- `src/main/java/dev/talos/runtime/command/ProcessCommandRunner.java`
- `src/test/java/dev/talos/runtime/command/*`
- `docs/public-installation.md`
- `docs/user/installation.md`
- `docs/user/quickstart.md`
- `tools/install-unix.sh`

Why a one-off patch is insufficient:

```text
Changing the executable string alone would not prove Linux support. The runner
environment, docs, install path, and command-profile tests must all line up.
```

## Goal

```text
Make the minimum Linux beta path honest: built-in verification command profiles
execute on Linux, the bounded environment is sufficient for Gradle wrapper
execution, and docs/artifact contracts state exactly what Linux support means.
```

## Non-Goals

- No shell execution expansion.
- No arbitrary command runner.
- No MCP or browser automation.
- No full SDKMAN/JBang/Homebrew packaging in this ticket.
- No GraalVM native image requirement.
- No claim of enterprise Linux fleet readiness.

## Implementation Notes

```text
Prefer an explicit OS/executable resolution seam over scattered `System`
conditionals. Keep argv-only execution, approval behavior, command-profile risk,
and network/interactive denial unchanged.
```

Minimum likely changes:

- Resolve built-in Gradle executable to `.\gradlew.bat` on Windows and `./gradlew` on POSIX.
- Keep the existing wrapper-existence guard but ensure the chosen executable is the executable being checked.
- Extend the command-runner environment allowlist in an OS-aware way for POSIX Gradle wrapper execution (`HOME`, `LANG`, `LC_ALL`, `TMPDIR`; consider `GRADLE_USER_HOME` only if needed and documented).
- Add tests for Windows and POSIX executable resolution without depending on the host OS.
- Add a Linux install/support statement that matches the implemented lane.

## Architecture Metadata

Capability:

- Bounded command verification and public beta installation.

Operation(s):

- `run`
- `verify`

Owning package/class:

- `dev.talos.runtime.command.CommandProfileRegistry`
- `dev.talos.runtime.command.ProcessCommandRunner`

New or changed tools:

- No new tool names.
- Existing `talos.run_command` profiles become platform-aware.

Risk, approval, and protected paths:

- Risk level: `BUILD_OR_TEST`
- Approval behavior: unchanged; command profiles still require approval.
- Protected path behavior: unchanged; command execution must not bypass protected-path policy.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged; verification commands do not checkpoint.
- Evidence obligation: command-profile execution evidence.
- Verification profile: Gradle built-ins on Windows and Linux.
- Repair profile: none.

Outcome and trace:

- Outcome/truth warnings: no new claims beyond actual command result.
- Trace/debug fields: existing command plan/policy/start/finish events should remain sufficient.

Refactor scope:

- Allowed: small command-profile executable-resolution seam.
- Forbidden: broad command subsystem rewrite or shell-based execution.

## Acceptance Criteria

- Built-in Gradle profiles choose `.\gradlew.bat` on Windows and `./gradlew` on POSIX.
- Tests prove executable choice without depending on the CI host OS.
- POSIX command execution has the minimum allowed environment needed for Gradle wrapper operation without reopening broad environment leakage.
- Public docs state the exact Linux beta support level.
- A Linux smoke lane exists or the ticket explicitly records the remaining manual evidence needed.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `CommandProfileRegistryTest` or equivalent for OS-aware Gradle executable resolution.
- Unit test: `ProcessCommandRunnerTest` for POSIX environment allowlist behavior.
- Integration/executor test: command planner accepts Linux wrapper path while preserving approval/risk policy.
- JSON e2e scenario: optional unless runtime command behavior changes beyond planning.
- Trace assertion: existing command trace remains populated.

Manual/TalosBench rerun:

- Prompt family: command verification on a fresh Linux workspace with `gradlew`.
- Workspace fixture: minimal Gradle project.
- Expected trace: command plan, approval required/granted, command finish.
- Expected outcome: command success or honest failure, never Windows-wrapper failure.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.command.*" --no-daemon
.\gradlew.bat check --no-daemon
```

Linux evidence command, from a Linux runner or shell:

```bash
./gradlew test --tests "dev.talos.runtime.command.*" --no-daemon
```

Automated Linux smoke lane:

```text
.github/workflows/beta-dev-ci.yml -> linux-command-portability
```

Current Linux lane obligations:

```text
1. Run Gradle itself through a stripped POSIX environment:
   env -i HOME="$HOME" PATH="$PATH" JAVA_HOME="$JAVA_HOME" LANG=... LC_ALL=... TMPDIR=... ./gradlew --no-daemon help
2. Run host-neutral command package tests:
   ./gradlew test --tests "dev.talos.runtime.command.*" --no-daemon
```

Local evidence captured in this worktree:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.command.CommandProfileRegistryTest" --tests "dev.talos.runtime.command.CommandToolPlannerTest" --tests "dev.talos.runtime.command.ProcessCommandRunnerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.command.*" --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.command.*" --tests "dev.talos.runtime.TurnProcessorCommandPolicyTest" --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest" --tests "dev.talos.runtime.toolcall.RunCommandOutputHandoffTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.run_command_tool_is_available_to_synchronized_audit_and_rejects_missing_gradle_wrapper_before_approval" --no-daemon
npm test --prefix site
npm run build --prefix site
.\gradlew.bat check --no-daemon
git diff --check
```

Result:

```text
BUILD SUCCESSFUL
```

Local Linux/WSL attempt before stripped-env CI addition:

```bash
wsl bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli && chmod +x ./gradlew && ./gradlew test --tests "dev.talos.runtime.command.*" --no-daemon'
```

Result:

```text
Blocked: Ubuntu WSL has no Java on PATH and JAVA_HOME is unset.
```

Local Linux/WSL stripped-env attempt after CI addition:

```bash
wsl bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli && chmod +x ./gradlew && env -i HOME="$HOME" PATH="$PATH" JAVA_HOME="$JAVA_HOME" LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}" TMPDIR="${TMPDIR:-/tmp}" ./gradlew --no-daemon help'
```

Result:

```text
Blocked: Ubuntu WSL has no Java on PATH and JAVA_HOME is unset.
```

Remaining release evidence:

```text
Observe the `linux-command-portability` GitHub Actions lane on this branch, or
run the Linux command above on a real Linux shell before treating this as release
packet evidence.
```

## Work-Test Cycle Notes

- Use the inner dev loop first.
- Add a `CHANGELOG.md` `Unreleased` note when implementation lands.
- Do not cut a candidate until Linux evidence is either automated or explicitly captured in a release packet.

## Known Risks

- Host-specific tests can become flaky if they depend on the current OS instead of an injected platform seam.
- Expanding environment variables can accidentally widen command process context; keep the allowlist minimal.
- Public Linux packaging can drift from source/developer Linux support unless docs are explicit.

## Review Notes - 2026-06-23

Codex review and Opus review agree on the production direction:

- `CommandRuntimePlatform` is the right small seam for deterministic Windows and
  POSIX command planning tests.
- The production blocker is addressed by selecting `.\gradlew.bat` on Windows
  and `./gradlew` on POSIX.
- The Gradle wrapper guard now checks the selected wrapper rather than accepting
  either wrapper file.
- The POSIX environment allowlist remains narrow and does not reintroduce broad
  environment passthrough.
- T861 must remain `in-progress` until Linux evidence exists.

Confirmed review findings to close before moving this ticket to `done`:

1. The added Linux CI lane originally ran `dev.talos.runtime.command.*`, but
   some command tests still assumed Windows defaults. Examples:
   `CommandProfileRegistryTest.gradleTestPlanUsesFixedProfileAndCallerArgs`
   asserts `gradlew.bat`, and `RunCommandToolTest.createGradleWrapper` creates
   only `gradlew.bat`. On Linux those tests can fail before proving the new
   behavior. This was fixed by making the command tests host-neutral and by
   creating the current-platform wrapper in `RunCommandToolTest`.
2. Nearby command-policy tests outside the Linux lane still pin Windows argv
   text. They do not block the current command-package lane, but they block a
   future Linux `test` or `check` lane unless made host-neutral. This was fixed
   for `TurnProcessorCommandPolicyTest`, `LocalTurnTraceCommandTest`, and
   `RunCommandOutputHandoffTest`.
3. Unit tests prove platform selection and allowlist contents, but they do not
   prove that a real Gradle wrapper succeeds under the filtered POSIX
   environment. A stripped-environment Gradle smoke was added to the Linux lane:

   ```bash
   env -i HOME="$HOME" PATH="$PATH" JAVA_HOME="$JAVA_HOME" \
     LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}" TMPDIR="${TMPDIR:-/tmp}" \
     ./gradlew --no-daemon help
   ```

4. Public docs may keep the Windows packaged / Linux source-developer framing,
   but the Linux support claim should not be treated as release truth until the
   Linux lane or an equivalent Linux shell run is green.

## Implementation Checkpoint - 2026-06-24

Implementation is complete and awaiting Opus review. Keep this ticket open; do
not move it to `done` until the command-profile implementation, wrapper guard,
POSIX environment allowlist, docs, site copy, and Linux CI smoke lane are
reviewed.

Local verification for this checkpoint:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.command.*" --tests "dev.talos.runtime.TurnProcessorCommandPolicyTest" --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest" --tests "dev.talos.runtime.toolcall.RunCommandOutputHandoffTest" --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.run_command_tool_is_available_to_synchronized_audit_and_rejects_missing_gradle_wrapper_before_approval" --no-daemon
npm test --prefix site
npm run build --prefix site
.\gradlew.bat check --no-daemon
```

Result:

```text
All commands passed locally on Windows.
```

## Review Follow-Up - 2026-06-24

Opus held closeout on two proof/honesty gaps:

- the POSIX environment allowlist was tested, but the Windows allowlist was not;
- the in-site documentation surface still contained stale `Windows-first public
  beta` wording after the landing page moved to the Windows-packaged /
  Linux-source-developer framing.

Follow-up implementation:

- added Windows environment allowlist coverage with exact cardinality, and
  cardinality-pinned the POSIX allowlist test;
- updated the stale in-site docs copy;
- added a site honesty guard banning `Windows-first` on the docs surface.

## Known Follow-Ups

- JReleaser/SDKMAN/JBang distribution strategy.
- OS-backed key custody for Linux secret-store master keys remains separate.
