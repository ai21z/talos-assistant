# [T878-open-medium] "profiles" naming collision: verification vs model GGUF profiles

Status: open
Priority: medium

## Evidence Summary

- Source: owner manual REPL testing + audit cross-check
- Date: 2026-06-26
- Talos version / commit: 0.10.6 / bb8b659a
- Verification status: source-verified

## Findings

Two unrelated concepts are both called "profiles", on adjacent surfaces:

- `/profiles [list|trust|revoke]` = workspace VERIFICATION profiles
  (`.talos/profiles.yaml`, the trusted commands `/verify` runs) --
  `ProfilesCommand.java:16-26,42-46`, group SECURITY.
- "managed GGUF profiles" / model profiles = `talos setup models --profile <name>`,
  referenced in the `/models` tip (`ModelsCommand.java:56`).

A user reading the `/models` tip about "profiles" naturally looks for a profiles
command, finds `/profiles`, and gets verification profiles -- a different concept.
The shared word misleads (the owner hit exactly this during manual testing).

## Goal

The two "profiles" concepts are disambiguated so a user is not misled: either
rename one surface (for example `/profiles` -> a verification-specific name, or stop
calling the model GGUFs "profiles" in the `/models` tip) or cross-reference them
explicitly so the distinction is obvious at the point of use.

## Likely code areas

- `src/main/java/dev/talos/cli/repl/slash/ProfilesCommand.java` (command name/summary)
- `src/main/java/dev/talos/cli/repl/slash/ModelsCommand.java` (the tip wording, lines 52-57)
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java` (security/models topic text)

## Non-Goals

- No change to the verification trust-chain behavior (T789); naming/wording only.
- If a command is renamed, keep the old name as an alias for compatibility.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- No single bare word "profiles" refers to both concepts on adjacent surfaces; the model tip and the `/profiles` command are clearly distinguished in wording and/or via a renamed-with-alias command.
- If `/profiles` is renamed, the prior name still resolves (alias).
- Regression test pins the disambiguated wording/aliasing.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.*" --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop; no version bump. Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.
- Cross-ref T876 (models help) and T877 (model discoverability).
