# [T923-open-low] DPAPI raw-key transport design follow-up

Status: open
Priority: low

## Evidence Summary

- Source: source review
- Date: 2026-07-02
- Talos version / commit: 0.10.6 / 7f75eb3070a6f32be890787decca0254e5972d4b
- Verification status: design follow-up recorded; not a 0.10.7 candidate blocker

Expected behavior:

```text
Windows DPAPI custody should minimize raw master-key exposure across helper
process boundaries where practical, without silently adding a native dependency
during stabilization.
```

Observed behavior:

```text
The PowerShell DPAPI helper returns the unprotected raw master key to Java as
base64 over the child process stdout pipe. This is not user console stdout, but
it can be visible to same-user process instrumentation/transcription/EDR.
```

## Classification

Primary taxonomy bucket: `TRACE_REDACTION`

Secondary buckets:

- `PERMISSION`

Blocker level: future milestone

Why this level:

```text
The current design still protects the key at rest with DPAPI CurrentUser. The
transport hardening requires a custody design choice and should not be bundled
with the public-main stabilization candidate unless a minimal no-dependency fix
is proven safe.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The best fix may be a small native/JNA or Windows credential API path, but that
is a dependency/platform decision. Keep this as an explicit follow-up rather
than smuggling it into T922.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/security/WindowsDpapiKeyCustody.java`
- `src/test/java/dev/talos/core/security/WindowsDpapiKeyCustodyTest.java`

## Goal

```text
Design and implement a lower-exposure DPAPI custody transport after the 0.10.7
public-main stabilization arc.
```

## Non-Goals

- No new dependency in the 0.10.7 stabilization arc.
- No change to non-Windows raw-key custody in this ticket.

## Architecture Metadata

Capability:

- local secret-store master-key custody

Operation(s):

- DPAPI protect/unprotect

Owning package/class:

- `WindowsDpapiKeyCustody`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: low for current candidate; higher for future custody design
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: design review before code
- Verification profile: Windows-focused unit/integration tests
- Repair profile: fail closed on custody failure

Outcome and trace:

- Outcome/truth warnings: docs should not overstate hardware-backed custody
- Trace/debug fields: none

Refactor scope:

- Allowed: future custody seam if chosen.
- Forbidden: unreviewed native dependency in stabilization arc.

## Acceptance Criteria

- This issue remains visible and non-duplicated.
- Any future implementation has a design note and Windows regression coverage.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.security.WindowsDpapiKeyCustodyTest" --no-daemon
```

## Known Follow-Ups

- Evaluate JNA/native Windows DPAPI or credential-manager custody after 0.10.7.

