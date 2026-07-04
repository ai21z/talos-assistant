# [T964-open-high] Setup Models Must Preserve User Config Policy

Status: open
Priority: high

## Evidence Summary

- Source: installed-product model setup release-confidence audit
- Date: 2026-07-04/05 Europe/Madrid
- Talos version / commit: 0.10.8 / 3369b237b297320915dad9dc25aa70769b2a4027
- Installed executable: `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Audit report: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\RELEASE-CONFIDENCE-SUMMARY.md`
- Before config: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\config-before-audit.yaml`
- After setup config: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\gptoss\config-gptoss-active.yaml`
- Command: `talos setup models --profile gpt-oss-20b --server-path <llama-server.exe> --write --force`

Redacted prompt/command sequence:

```powershell
# Initial user config contained a custom permissions deny block for protected paths.
talos setup models --profile gpt-oss-20b --server-path <llama-server.exe> --write --force
Get-Content C:\Users\arisz\.talos\config.yaml
```

Expected behavior:

```text
Model setup updates model/backend configuration without silently deleting
unrelated user-owned config sections such as permissions policy. If a destructive
rewrite is intended, Talos must label it explicitly before writing and preserve a
backup.
```

Observed behavior:

```text
The setup command rewrote the config and dropped the existing permissions block
that denied protected reads for .env, .env.*, secrets/**, and protected/**.
Talos did create a backup, but the active runtime policy changed silently.
```

## Classification

Primary taxonomy bucket:

- `PERMISSION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `UNSUPPORTED_CAPABILITY`

Blocker level:

- release blocker

Why this level:

```text
The setup wizard/model setup path must not erase user security policy while
preparing a model. Silent policy loss changes protected-read behavior and can
invalidate manual audit evidence.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Document that --force overwrites everything.
```

Architectural hypothesis:

```text
The setup writer likely emits a fresh config document from model setup state
instead of merge-preserving unrelated user config sections. The writer needs an
ownership boundary: model setup owns llm/engines/embed/rag model knobs, not
arbitrary user policy.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/commands/SetupCommand.java`
- config writer/serializer helpers
- config loading/merge tests
- user docs for setup/config preservation

Why a one-off patch is insufficient:

```text
Permissions are the concrete observed casualty, but the invariant applies to all
unknown or unrelated user config sections. Setup should not become a destructive
configuration formatter.
```

## Goal

```text
`talos setup models --write` preserves user-owned config sections not controlled
by model setup, especially permissions policy, while still writing the selected
model profile accurately.
```

## Non-Goals

- No changing the semantics of `permissions` policy.
- No removing config backups.
- No broad config schema rewrite unless required for safe merge ownership.
- No silently accepting invalid YAML.

## Implementation Notes

Preferred direction:

- Parse existing user config structurally.
- Replace only the model/setup-owned subtree(s).
- Preserve unrelated sections, comments if practical but not required for this
  ticket.
- If preserving a section is impossible, print an explicit destructive-change
  warning and require a stronger explicit flag than ordinary `--force`.

## Architecture Metadata

Capability:

- Setup/config write preserving local policy.

Operation(s):

- config read
- config write

Owning package/class:

- Setup command and config writer.

New or changed tools:

- No REPL tool changes expected.

Risk, approval, and protected paths:

- Risk level: high, because policy loss can expose protected content in later
  turns.
- Approval behavior: setup write remains explicit via `--write`; destructive
  policy removal must not be implicit in `--force`.
- Protected path behavior: preserved permissions must continue to apply after
  model setup.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable; backup remains required.
- Evidence obligation: setup output should identify backup path and whether
  unrelated sections were preserved.
- Verification profile: config reload/status confirms permissions survive.
- Repair profile: not applicable.

Outcome and trace:

- Outcome/truth warnings: setup must not imply only model config changed if it
  removed unrelated policy.
- Trace/debug fields: not applicable.

Refactor scope:

- Allowed: narrow merge-preserving config writer helper.
- Forbidden: broad config architecture rewrite or migration of unrelated config
  keys.

## Acceptance Criteria

- Regression test starts with a config containing a `permissions` block and
  model setup writes a new profile while preserving that block.
- Regression test preserves an unknown top-level section or explicitly documents
  why unknown sections are not supported.
- `talos status --verbose` after setup reports the new model while protected
  read policy remains active.
- Setup output does not hide destructive behavior.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- CLI/config writer test: setup preserves `permissions`.
- CLI/config writer test: setup backup is still created.
- Installed smoke: configure Qwen, add permission deny, switch to GPT-OSS, deny
  policy remains in active config.

Manual/TalosBench rerun:

- Prompt family: protected `.env` read after model switch.
- Workspace fixture: `.env` plus permissions deny policy.
- Expected trace: config-deny or approval behavior remains exactly as intended
  by preserved config.
- Expected outcome: no silent downgrade of protected-read policy.

Commands:

```powershell
.\gradlew.bat test --tests "*Setup*" --tests "*Config*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Behavior-changing closeout requires a CHANGELOG entry under `## [Unreleased]`.
- This should be fixed before another installed-product two-model audit, because
  model switching can otherwise invalidate protected-read evidence.

## Known Risks

- Preserving comments may be harder than preserving values. Value preservation is
  the release-critical invariant.

## Known Follow-Ups

- Consider a `talos config diff` preview before setup writes if setup grows more
  complex.
