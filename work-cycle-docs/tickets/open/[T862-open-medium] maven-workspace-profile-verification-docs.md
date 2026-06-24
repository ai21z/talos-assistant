# [T862-open-medium] Maven Workspace Profile Verification Docs

Status: open
Priority: medium

## Evidence Summary

- Source: static code and docs review
- Date: 2026-06-23
- Talos version / commit: `0.10.5` / `723d4cd2`
- Branch: `v0.9.0-beta-dev`
- Model/backend: not applicable
- Workspace fixture: not applicable
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: no runtime diff; product-support gap
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: not run

Redacted prompt sequence:

```text
Static product-readiness review asked whether Maven support requires replacing
Gradle or adding built-in Maven command profiles.
```

Expected behavior:

```text
Talos should support Maven projects through the existing trust-pinned workspace
profile mechanism, and documentation/tests should make that path discoverable
without weakening command trust boundaries.
```

Observed behavior:

```text
The model-callable command profile docs list only built-in Gradle profiles.
Workspace `ws:` profiles are already trust-pinned, fixed-argv, approval-forced,
and treated as verification-class command evidence, but there is no visible
Maven recipe or deterministic proof that `./mvnw verify` is the intended path.
```

## Classification

Primary taxonomy bucket:

- `COMMAND_PROFILE`

Secondary buckets:

- `VERIFICATION`
- `DEVELOPER_EXPERIENCE`
- `DOCS`

Blocker level:

- candidate follow-up

Why this level:

```text
Maven support matters for Java and enterprise audiences, but it is not the same
as Talos migrating its own build to Maven and should not block the Linux beta
portability work.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add always-trusted built-in Maven profiles that mirror the Gradle built-ins.
```

Architectural hypothesis:

```text
Maven is best supported first through `.talos/profiles.yaml`, because workspace
profiles already have the right trust model: fixed argv, SHA-256 declaration
pin, no caller args, no network/interactive capability, and per-run approval.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/command/WorkspaceCommandProfilesLoader.java`
- `src/main/java/dev/talos/runtime/command/CommandToolPlanner.java`
- `src/main/java/dev/talos/runtime/verification/CommandVerificationEvidence.java`
- `src/main/java/dev/talos/runtime/command/RunCommandTool.java`
- `src/test/java/dev/talos/runtime/command/*`
- `src/test/java/dev/talos/runtime/verification/*`
- `docs/user/commands.md`
- `docs/user/approvals-and-permissions.md`
- `docs/user/quickstart.md`

Why a one-off patch is insufficient:

```text
Adding a Maven profile to one registry would not expose it safely. The planner,
argument policy, docs, trust pin, verification upgrade, and user-facing command
surface must agree.
```

## Goal

```text
Make Maven verification support explicit through trusted workspace profiles,
with docs and tests showing `./mvnw -B --no-transfer-progress verify` as the
recommended Maven project path.
```

## Non-Goals

- No migration of Talos' own build from Gradle to Maven.
- No built-in `maven_install` profile.
- No write outside workspace through `~/.m2` as a claimed safe mutation.
- No caller-supplied arbitrary Maven arguments.
- No network guarantee; Maven dependency resolution behavior must be documented honestly.

## Implementation Notes

```text
Start with documentation and deterministic tests around the existing `ws:`
profile machinery. Only add built-in Maven profiles later if the trust model is
kept equivalent to workspace profiles.
```

Recommended `.talos/profiles.yaml` example:

```yaml
profiles:
  - id: maven_verify
    executable: ./mvnw
    args: ["-B", "--no-transfer-progress", "verify"]
    timeout_ms: 600000
    expected_writes: ["target/"]
```

## Architecture Metadata

Capability:

- Trusted workspace verification profile for Maven projects.

Operation(s):

- `run`
- `verify`

Owning package/class:

- `dev.talos.runtime.command.WorkspaceCommandProfilesLoader`
- `dev.talos.runtime.command.CommandToolPlanner`
- `dev.talos.runtime.verification.CommandVerificationEvidence`

New or changed tools:

- No new tool names.
- Existing `talos.run_command` uses trusted `ws:<id>` profiles.

Risk, approval, and protected paths:

- Risk level: `BUILD_OR_TEST`
- Approval behavior: per-run approval remains required.
- Protected path behavior: `.talos/profiles.yaml` remains protected by workspace/profile policy and trust pinning.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: no checkpoint for verification command.
- Evidence obligation: successful trusted `ws:` command after mutation may upgrade readback-only evidence.
- Verification profile: trusted workspace profile.
- Repair profile: none.

Outcome and trace:

- Outcome/truth warnings: Maven verification must be reported as command evidence only when the trusted profile exits 0.
- Trace/debug fields: existing command plan/policy/start/finish events.

Refactor scope:

- Allowed: docs, tests, and small wording/schema updates.
- Forbidden: broad command registry rewrite or unsafe built-in Maven command expansion.

## Acceptance Criteria

- User docs include a Maven `ws:maven_verify` example and the `/profiles trust` flow.
- Docs distinguish Talos' own Gradle build from Talos support for Maven workspaces.
- Tests prove a trusted Maven-shaped workspace profile registers and plans as fixed argv with no caller args.
- Tests prove untrusted or changed Maven profile declarations fail closed before approval.
- Tests prove a successful trusted `ws:maven_verify` after mutation is accepted as verification-class command evidence.
- No docs imply Maven command execution is network-free unless the workspace has already resolved dependencies or Maven is configured offline.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `WorkspaceCommandProfilesLoaderTest` for Maven-shaped profile parsing.
- Unit test: `CommandToolPlannerTest` for trusted `ws:maven_verify` fixed argv and caller-arg rejection.
- Unit test: `CommandVerificationEvidence` coverage through `StaticTaskVerifierTest` or a package-local verification test.
- Docs test: command docs mention Maven through workspace profiles, not built-in arbitrary shell.

Manual/TalosBench rerun:

- Prompt family: verify a Maven project using `ws:maven_verify`.
- Workspace fixture: minimal Maven wrapper project.
- Expected trace: trust pin state, approval required/granted, command finish.
- Expected outcome: command evidence is named accurately.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.command.*" --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.docs.*" --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- Add a `CHANGELOG.md` `Unreleased` note if docs/tests/runtime wording change.
- Do not add a built-in Maven profile in this ticket unless a design note explains why workspace profiles are insufficient.

## Known Risks

- Maven may write to local dependency caches outside the workspace; docs must not describe that as workspace-contained mutation.
- Maven can perform network access during dependency resolution; the Talos command profile disables shell/network primitives but cannot make Maven itself offline unless Maven is configured that way.
- Wrapper scripts differ by OS; this ticket should compose with T861's Linux portability work.

## Known Follow-Ups

- Optional future built-in Maven profiles with the same trust properties as workspace profiles.
- Optional project-type profile suggestions from `talos doctor`.
