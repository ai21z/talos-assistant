# [T863-done-medium] Tamper-Evident Local Trace Chain

Status: done
Priority: medium

## Evidence Summary

- Source: static docs/code review and existing Wave 6 evidence record
- Date: 2026-06-23
- Talos version / commit: `0.10.5` / `723d4cd2`
- Branch: `v0.9.0-beta-dev`
- Model/backend: not applicable
- Workspace fixture: not applicable
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: no runtime diff; future integrity gap
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: not run

Redacted prompt sequence:

```text
Product-readiness review identified trace integrity as a high-leverage future
upgrade, but not a beta blocker if current copy remains honest.
```

Expected behavior:

```text
If Talos later uses stronger language such as tamper-evident, provable receipt,
or cryptographic audit trail, local traces must have a verifiable integrity
chain and verification command.
```

Observed behavior:

```text
README and architecture docs correctly disclose that local traces are plaintext
diagnostic artifacts, not tamper-evident logs. The Wave 6 evidence record maps
trace integrity findings as deferred.
```

## Classification

Primary taxonomy bucket:

- `TRACE_REDACTION`

Secondary buckets:

- `AUDIT_EVIDENCE`
- `OUTCOME_TRUTH`
- `RELEASE_CLAIMS`

Blocker level:

- future milestone

Why this level:

```text
Current beta copy can remain honest by saying traces are local diagnostic
evidence, not tamper-evident audit logs. This becomes a blocker only if product
copy or enterprise positioning claims cryptographic auditability.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Say traces are tamper-proof because they contain SHA-256 hashes.
```

Architectural hypothesis:

```text
Trace integrity needs an append/verify model with chained event hashes or
receipt hashes, explicit verification UX, and honest wording about the limits of
local same-user storage.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/trace/*`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
- `src/main/java/dev/talos/cli/repl/slash/*Trace*`
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `README.md`
- `docs/user/local-privacy-and-artifacts.md`

Why a one-off patch is insufficient:

```text
Adding hashes to each event is not enough. The user needs a verifier, legacy
trace handling, redaction-safe fields, and copy that does not imply impossible
protection against same-user filesystem tampering.
```

## Goal

```text
Design and implement a local trace integrity chain that can detect post-write
trace modification under normal local-file assumptions, while preserving honest
limits for same-user attacker and disk-access scenarios.
```

## Non-Goals

- No claim of tamper-proof storage.
- No remote attestation.
- No hardware-backed signing requirement in the first version.
- No enterprise compliance certification claim.
- No weakening trace redaction to get stronger hashes.

## Implementation Notes

```text
Start with a design note before code. Candidate shape: per-event canonical JSON
hash, previous hash linkage, trace-level final receipt, and a `talos trace
verify` or slash-command verification path. Decide separately whether signatures
are local-key, OS-backed, or omitted in v1.
```

## Architecture Metadata

Capability:

- Local trace integrity verification.

Operation(s):

- `trace`
- `verify`

Owning package/class:

- `dev.talos.runtime.trace`
- `dev.talos.runtime.JsonSessionStore`

New or changed tools:

- No model-callable tool required.
- Possible user-facing slash command or launcher command for trace verification.

Risk, approval, and protected paths:

- Risk level: read-only verification unless repair/migration writes are added.
- Approval behavior: no approval for read-only verification; ask before migration/rewriting legacy traces.
- Protected path behavior: trace verifier must not reveal protected content or raw redacted fields.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none.
- Evidence obligation: verify hash chain and report exact broken link when corrupt.
- Verification profile: trace-integrity verifier.
- Repair profile: none in first version.

Outcome and trace:

- Outcome/truth warnings: distinguish valid, invalid, legacy-unverifiable, and missing trace.
- Trace/debug fields: do not include raw content solely to make hashing easier.

Refactor scope:

- Allowed: trace serialization and verification seams.
- Forbidden: broad trace schema rewrite without legacy compatibility.

## Acceptance Criteria

- Design note records threat model and non-claims.
- New traces include enough integrity metadata to detect event/body modification.
- Verification command reports valid, invalid, missing, and legacy-unverifiable states distinctly.
- Existing legacy trace loading remains compatible.
- Docs update from "not tamper-evident" only after implementation and tests exist.
- No docs use "tamper-proof."
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: valid trace verifies.
- Unit test: modified event breaks the chain.
- Unit test: removed/reordered event breaks the chain.
- Unit test: legacy trace is reported as legacy-unverifiable, not corrupt.
- Integration/executor test: `/last trace` or trace loader remains compatible.
- Trace assertion: verification result itself does not leak protected content.

Manual/TalosBench rerun:

- Prompt family: normal file mutation with trace capture, then manual trace edit and verify.
- Workspace fixture: simple text workspace.
- Expected trace: integrity metadata present.
- Expected outcome: corruption detected after edit.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.*" --tests "dev.talos.runtime.JsonSessionStore*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- This is deferred beyond beta unless public claims change.
- Do not use this ticket to delay the beta if current copy remains honest about plaintext, non-tamper-evident traces.
- Add `CHANGELOG.md` `Unreleased` notes only when implementation begins.

## Known Risks

- Local hash chains detect modification but do not prevent deletion or same-user replacement.
- Signing introduces key-custody questions that overlap with secret-store custody.
- Canonical JSON mistakes can create false corruption reports.

## Known Follow-Ups

- Optional OS-backed signing.
- Optional append-only storage integration.
- Optional release/audit packet receipt verification.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as deferred out of this main-merge line: future private-document / document-beta / v1 / future-capability scope, not current main-merge work.

Closed by independent review as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
