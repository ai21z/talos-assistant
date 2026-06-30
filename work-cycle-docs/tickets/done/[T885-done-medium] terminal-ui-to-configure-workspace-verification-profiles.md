# [T885-done-medium] Terminal-UI flow to configure workspace verification profiles

Status: done
Priority: medium
Blocker level: completed implementation. This was originally captured as a
future milestone discussion item; the owner later selected it for implementation.

## Evidence Summary

- Source: owner manual REPL testing + discussion (2026-06-27)
- Date: 2026-06-27
- Talos version / commit: 0.10.6 / 4f8f50a7
- Verification status: implemented and full-check verified on 2026-06-30

Observed / request: `/profiles` (workspace verification profiles in
`.talos/profiles.yaml`) is not in `/help` or `/help all`, and the owner is fine with
that. The actual ask: give the user a way to *configure* a verification profile from
the terminal UI, rather than only hand-editing `.talos/profiles.yaml`. Raised
together with [T886] (the model configure/test/guide arc); the owner explicitly
framed both as discussion, not implementation.

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `PERMISSION` (trust of a declared profile is an approval decision)

Blocker level:

- future milestone

Why this level:

```text
Owner asked to discuss, not build, for now. It also touches the trust surface
(profile trust), so it must be designed before it is implemented, not patched in.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add a /profiles configure command that writes profiles.yaml.
```

Architectural hypothesis:

```text
Configuring a verification profile is two separable concerns that must not be
collapsed: (1) DECLARING a profile (what to verify: build/test/lint commands,
expected artifacts) and (2) TRUSTING it (allowing Talos to act on it). Today the
trust chain pins the profile by content hash (SHA-256) and requires explicit
approval before a declared profile is trusted (T789/T791). A terminal-UI must own
declaration ergonomics WITHOUT becoming a back door that auto-trusts. Declared !=
trusted. The UI proposes; the user (and the existing approval/pin owner) disposes.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/repl/slash/ProfilesCommand.java` (current inspect/trust surface)
- the verification-profile model + `.talos/profiles.yaml` loader
- the profile trust/pin owner (SHA-256 pin + approval, T789/T791)

Why a one-off patch is insufficient:

```text
A naive "write the yaml from a wizard" change would let a UI-declared profile be
treated as trusted, weakening the verification trust invariant. The change has to
route through the existing pin+approval owner.
```

## Goal

```text
A user can declare/edit a workspace verification profile from the terminal UI, and
the result is still only trusted through the existing SHA-256 pin + explicit
approval chain. A profile declared via the UI is NOT auto-trusted; nothing Talos
later verifies against is trusted without the pin/approval step.
```

## Non-Goals

- No auto-trust of a UI-declared profile. Declared != trusted, always.
- No weakening or bypassing the SHA-256 pin or the approval gate (T789/T791).
- No overwriting `~/.talos/config.yaml` or `~/.talos/secrets` without explicit
  owner confirmation.
- No LLM classifier deciding what is safe to verify or trust.
- No shell/browser/MCP/multi-agent behavior.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Implementation Notes

```text
Keep declaration (UI/editor ergonomics) strictly separate from trust (pin +
approval). The UI emits a proposed profile; trust still flows through the existing
owner. Consider a confirm/diff step that shows the exact bytes to be written and
the resulting content hash before anything is pinned.
```

Implemented shape:

- `/profiles configure <id> --exec <executable> [--arg <argv>]...
  [--timeout-ms <ms>] [--expected-write <workspace-path>]...` declares or edits
  one workspace verification profile.
- The command previews the proposed `.talos/profiles.yaml` bytes and the
  resulting declaration SHA-256 through the approval gate before any write.
- The write is checkpointed before `.talos/profiles.yaml` is changed; checkpoint
  failure blocks the write.
- The resulting declaration remains `UNTRUSTED_NEW` or `UNTRUSTED_CHANGED`
  until `/profiles trust` pins the exact SHA-256 through the existing trust
  store.
- Trace/audit metadata records profile id, declaration SHA-256, approval choice,
  trust state, checkpoint status/id, and whether the profile replaced an
  existing declaration; raw profile YAML is not stored in trace metadata.

## Architecture Metadata

Capability:

- workspace verification profile configuration (declaration ergonomics only)

Operation(s):

- write (`.talos/profiles.yaml`, gated), verify (downstream, unchanged)

Owning package/class:

- `ProfilesCommand` + the verification-profile trust/pin owner (not a new god-class)

New or changed tools:

- none for the model; a REPL flow/subcommand at most

Risk, approval, and protected paths:

- Risk level: medium (trust-surface adjacent)
- Approval behavior: a UI-declared profile must go through the same explicit
  approval as today; no implicit trust
- Protected path behavior: `.talos/profiles.yaml` write is gated; `~/.talos`
  secrets/config untouched

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: checkpoint before writing `.talos/profiles.yaml`
- Evidence obligation: record the proposed bytes + resulting content hash + the
  approval decision
- Verification profile: unchanged downstream
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: never report a profile as "trusted" until it is pinned+approved
- Trace/debug fields: profile hash, declared-vs-trusted state, approval choice

Refactor scope:

- `<allowed: extract a small profile-declaration helper>`
- `<forbidden: merging declaration and trust, or a broad ProfilesCommand rewrite>`

## Acceptance Criteria

- A user can declare/edit a verification profile from the terminal UI.
- A UI-declared profile is NOT trusted until it passes the existing SHA-256 pin +
  approval chain; a regression test proves declared != trusted.
- `.talos/profiles.yaml` writes are checkpointed and the proposed bytes + hash are
  shown before writing.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: a UI-declared profile is in a declared-not-trusted state until
  pinned+approved (`ProfilesCommandTest.configureDeclaresProfileAfterApprovalButDoesNotTrustIt`)
- Integration test: write path is checkpointed and gated
  (`configureDeclaresProfileAfterApprovalButDoesNotTrustIt`,
  `configureCheckpointFailureBlocksWriteAfterApproval`)
- Trace assertion: profile hash + approval choice recorded
  (`configureRecordsTraceEventWithHashApprovalChoiceAndTrustState`)

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ProfilesCommandTest"
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ProfilesCommandTest" --tests "dev.talos.cli.repl.slash.VerifyCommandTest" --tests "dev.talos.runtime.command.WorkspaceCommandProfilesLoaderTest" --tests "dev.talos.runtime.command.WorkspaceProfileTrustStoreTest" --tests "dev.talos.cli.repl.slash.CheckpointCommandTest" --tests "dev.talos.runtime.trace.LocalTurnTraceCheckpointRecorderTest" --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest"
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.SimpleCommandsTest" --tests "dev.talos.cli.repl.SlashCommandCompleterTest" --tests "dev.talos.cli.repl.WorkspaceCommandTemplatesTest"
.\gradlew.bat check --no-daemon
```

All commands passed on 2026-06-30. The first focused run was red before
implementation because the `/profiles configure` constructor/subcommand did not
exist, satisfying the TDD red step.

## Work-Test Cycle Notes

- Inner dev loop when implemented. Do not bump version outside candidate closeout.
- Behavior-changing closeout adds a one-line `## [Unreleased]` CHANGELOG entry.

## Known Risks / Residual Scope

- Easy to accidentally auto-trust a declared profile; the declared != trusted
  tests are the guard.
- The terminal flow is intentionally flag-based rather than a full-screen wizard.
  That keeps the trust path deterministic and scriptable for the beta line.

## Known Follow-Ups

- None required for this ticket. A richer interactive wizard can be considered
  later if it preserves the same declaration/trust separation.
