# [T922-done-medium] Secret-store scope and protected vocabulary hardening

Status: done
Priority: medium

## Evidence Summary

- Source: source review
- Date: 2026-07-02
- Talos version / commit: 0.10.6 / 7f75eb3070a6f32be890787decca0254e5972d4b
- Verification status: latent FileSecretStore scope traversal and missing common protected path tokens

Expected behavior:

```text
Secret-store scope names cannot escape the configured base directory, and common
credential/config files are protected by the canonical path classifier.
```

Observed behavior:

```text
FileSecretStore.safe(scope) trims but does not sanitize separators or traversal.
ProtectedPathTokens lacks explicit coverage for .kube, .npmrc, .netrc, .ppk,
.docker/config.json, and id_ed25519_sk.
```

## Classification

Primary taxonomy bucket: `PERMISSION`

Secondary buckets:

- `TRACE_REDACTION`

Blocker level: candidate follow-up

## Architectural Hypothesis

Architectural hypothesis:

```text
Scope sanitization belongs inside FileSecretStore before resolving the scope
directory. Protected vocabulary expansion belongs only in ProtectedPathTokens,
the canonical classifier used by permission, redaction, evidence, and repair
surfaces.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/secret/FileSecretStore.java`
- `src/test/java/dev/talos/core/secret/*`
- `src/main/java/dev/talos/safety/ProtectedPathTokens.java`
- `src/test/java/dev/talos/safety/ProtectedPathTokensTest.java`

## Goal

```text
Close cheap deterministic trust hardening gaps without redesigning secret
custody or protected-path policy.
```

## Non-Goals

- No DPAPI transport redesign.
- No new native/JNA dependency.
- No protected-path LLM classifier.

## Architecture Metadata

Capability:

- local secret store and protected-path classification

Operation(s):

- write/read/delete local secret entries; classify paths

Owning package/class:

- `FileSecretStore`, `ProtectedPathTokens`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged
- Protected path behavior: broaden common credential-file recognition

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: deterministic unit regression
- Verification profile: focused tests, full check
- Repair profile: fail closed on unsafe scope names by sanitizing to local filename-like scope

Outcome and trace:

- Outcome/truth warnings: protected classifier expansion may redact/deny more paths
- Trace/debug fields: existing protected-kind surfaces inherit classifier

Refactor scope:

- Allowed: minimal helper extraction.
- Forbidden: broad secret-store or policy redesign.

## Acceptance Criteria

- Scope values with separators, drive syntax, or `..` cannot write outside the secret-store base.
- Existing valid scope/key behavior remains compatible.
- `.kube`, `.npmrc`, `.netrc`, `.ppk`, `.docker/config.json`, and `id_ed25519_sk` classify protected.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Completion Evidence

- Added a behavioral regression proving `../<sibling>` scope input stores under
  the configured secret-store base and does not create a sibling directory.
- Added a package-local scope sanitizer regression for blank, all-dot,
  traversal, drive-syntax, and UNC-shaped scope values while preserving ordinary
  scope names.
- Expanded the canonical protected-path classifier to cover `.kube`,
  `.docker`, `.npmrc`, `.netrc`, `.ppk`, and `id_ed25519_sk`; readback
  sensitivity inherits the same classifier coverage.

## Tests / Evidence

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.secret.*" --tests "dev.talos.safety.ProtectedPathTokensTest" --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
