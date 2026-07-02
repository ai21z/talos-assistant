# [T918-open-high] Redacted audit snapshot output containment

Status: open
Priority: high

## Evidence Summary

- Source: public CI failure triage plus source review
- Date: 2026-07-02
- Talos version / commit: 0.10.6 / 7f75eb3070a6f32be890787decca0254e5972d4b
- Trace path or `/last trace` summary: not applicable
- Verification status: source bug confirmed; exact GitHub CI root cause remains unproven until post-fix CI evidence

Expected behavior:

```text
RedactedAuditSnapshotWriter must reject an output directory that resolves inside
the audited workspace even when the raw output path is an alias or lexical form
that does not start with the workspace path string.
```

Observed behavior:

```text
The workspace is canonicalized with toRealPath(), but output uses only
toAbsolutePath().normalize() before startsWith(workspace). Alias-shaped paths can
therefore bypass the inside-workspace guard.
```

## Classification

Primary taxonomy bucket: `TRACE_REDACTION`

Blocker level: release blocker

Why this level:

```text
The audit snapshot writer is part of the evidence/redaction surface. Output
containment must fail closed before public candidate evidence is trusted.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The containment check needs one canonicalization owner inside
RedactedAuditSnapshotWriter. Tests should not rely on host 8.3 short-name
support; expose a narrow canonicalization seam/helper for deterministic alias
regression coverage.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/policy/RedactedAuditSnapshotWriter.java`
- `src/test/java/dev/talos/runtime/policy/RedactedAuditSnapshotWriterTest.java`

## Goal

```text
Reject any output path whose canonical target is inside the workspace, and fail
closed when canonicalization cannot establish safety.
```

## Non-Goals

- No broad audit-snapshot redesign.
- No change to protected-content redaction vocabulary.
- No release/tag publication.

## Architecture Metadata

Capability:

- local audit evidence snapshot

Operation(s):

- read workspace, write audit artifact outside workspace

Owning package/class:

- `dev.talos.runtime.policy.RedactedAuditSnapshotWriter`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high
- Approval behavior: not an interactive Talos tool
- Protected path behavior: unchanged redaction/omission policy

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: deterministic regression plus CI rerun
- Verification profile: focused unit test, full `check`, GitHub Actions
- Repair profile: fail closed

Outcome and trace:

- Outcome/truth warnings: none
- Trace/debug fields: none

Refactor scope:

- Allowed: small package-private/helper seam for canonicalization.
- Forbidden: broad snapshot writer rewrite.

## Acceptance Criteria

- Alias/canonical output-inside-workspace path is rejected.
- Canonicalization failure rejects output instead of allowing it.
- Normal outside-workspace snapshot still works.
- Existing inside-workspace CLI rejection still works.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.RedactedAuditSnapshotWriterTest" --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Known Risks

- Host-specific Windows alias behavior is hard to reproduce portably; test via a deterministic seam.

