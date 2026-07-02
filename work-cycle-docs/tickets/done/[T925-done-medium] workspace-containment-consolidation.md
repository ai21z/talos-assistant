# [T925-done-medium] Workspace containment consolidation

Status: done
Priority: medium

## Evidence Summary

- Source: post-0.10.7 public-main stabilization code review
- Date: 2026-07-02
- Talos version / commit: 0.10.7 / accd47248a88a2f0d0a2019e2b789ecc7106d483
- Verification status: implemented with red/green regression coverage and full `check`

Expected behavior:

```text
Every workspace-boundary check that can lead to read, write, delete, restore,
approval-memory, protected-path classification, or evidence claims should use a
single separator-safe, symlink-aware containment primitive or a documented
equivalent. Windows case handling must not degrade into prefix-string checks
that confuse sibling directories.
```

Observed behavior:

```text
T918 fixed the red-CI RedactedAuditSnapshotWriter containment instance by
canonicalizing output through the nearest existing parent and failing closed.
Other containment surfaces still use local ad hoc checks. The confirmed
remaining defect is FileBundleCheckpointStore.startsWithWorkspace, whose
Windows fallback lowercases both strings and uses String.startsWith without a
separator boundary. That can classify a name-prefix sibling such as
C:\parent\workspace-sibling as inside C:\parent\workspace.
```

Source anchors:

- `src/main/java/dev/talos/runtime/policy/RedactedAuditSnapshotWriter.java`
  now uses `canonicalizeOutputForContainment(...)`; this is the good T918
  shape to preserve or generalize.
- `src/main/java/dev/talos/runtime/checkpoint/FileBundleCheckpointStore.java`
  resolves checkpoint capture and restore targets with `ws.resolve(...).normalize()`
  and calls local `startsWithWorkspace(...)`.
- `FileBundleCheckpointStore.startsWithWorkspace(...)` returns true on Windows
  when `resolved.toString().toLowerCase(...).startsWith(workspace.toString().toLowerCase(...))`;
  this is separator-unsafe.
- `src/main/java/dev/talos/tools/impl/DeletePathTool.java` has a lexical
  `rejectWorkspaceRoot(...)` guard after `WorkspaceOperationToolSupport.resolveAllowed(...)`.
  Normal delete execution is already sandbox-gated, so this is consolidation
  debt rather than a confirmed delete escape.
- Additional direct containment checks exist in approval, command, verifier,
  slash-command, and outcome paths and should be classified before replacing.

## Classification

Primary taxonomy bucket: `PERMISSION`

Secondary buckets:

- `CHECKPOINT`
- `TRACE_REDACTION`
- `OUTCOME_TRUTH`

Blocker level: candidate follow-up

Why this level:

```text
The immediate public red-CI containment failure was fixed by T918 and verified
green in GitHub Actions. The remaining confirmed bug is in checkpoint
capture/restore containment, a trust-relevant write/delete path, but the
escaping checkpoint entry would require a malformed or compromised checkpoint
manifest or an upstream capture gap. This should be fixed before a public
release, but it does not invalidate the 0.10.7 stabilization candidate by
itself.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Containment policy is duplicated across local helpers. The repo needs a small
owned primitive, not another one-off patch: one helper should canonicalize the
workspace, resolve existing parents for missing targets, preserve fail-closed
behavior where safety-critical, and provide separator-safe Windows fallback only
where canonical realpath comparison cannot be used.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/security/Sandbox.java`
- `src/main/java/dev/talos/runtime/checkpoint/FileBundleCheckpointStore.java`
- `src/main/java/dev/talos/tools/impl/DeletePathTool.java`
- `src/main/java/dev/talos/tools/impl/WorkspaceOperationToolSupport.java`
- `src/main/java/dev/talos/runtime/SessionApprovalPolicy.java`
- `src/main/java/dev/talos/safety/ProtectedWorkspacePaths.java`

Why a one-off patch is insufficient:

```text
T918 already showed the same containment family in a different artifact writer.
Leaving every class to invent `startsWith` semantics repeats the same Windows
alias, junction, missing-path, and sibling-prefix mistakes. The smallest
durable fix is to centralize the primitive and migrate the security-relevant
callers first.
```

## Goal

```text
Introduce or select one workspace-containment owner and migrate the confirmed
checkpoint restore/capture bug plus the delete root-guard redundancy to it,
with deterministic tests that prove separator-safe Windows containment and
missing-target behavior.
```

## Non-Goals

- No public release/tag/winget cut in this ticket.
- No broad rewrite of every path check in one pass.
- No weakening of sandbox, protected-path, approval, checkpoint, trace, or
  evidence gates.
- No change to model behavior or prompt wording.
- No claim that `DeletePathTool` currently has a confirmed escape without a
  red regression proving it.

## Implementation Notes

```text
Start with a red test against FileBundleCheckpointStore using a deterministic
path/canonicalization seam or injected containment primitive rather than a real
Windows 8.3 alias. Then replace the separator-unsafe fallback. After that,
make DeletePathTool's root guard use the same primitive or remove redundant
lexical assumptions while preserving the existing sandbox gate.
```

## Architecture Metadata

Capability:

- workspace-bound filesystem containment

Operation(s):

- checkpoint capture
- checkpoint restore
- file delete root protection
- path classification audit

Owning package/class:

- Existing owner candidate: `dev.talos.core.security.Sandbox`
- New owner candidate: a small containment primitive under `dev.talos.core.security`
  or `dev.talos.safety`

New or changed tools:

- none expected

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged
- Protected path behavior: must not weaken protected path classification

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: checkpoint capture/restore must reject prefix-sibling
  escapes deterministically.
- Evidence obligation: source tests plus focused checkpoint restore regression.
- Verification profile: unit tests first; full `check` if runtime code changes.
- Repair profile: fail closed on unresolvable security-relevant containment.

Outcome and trace:

- Outcome/truth warnings: no user-facing success claim after a rejected restore
  target.
- Trace/debug fields: unchanged unless existing checkpoint trace needs to record
  a rejected escaped target.

Refactor scope:

- Allowed: a small containment helper and focused caller migration.
- Forbidden: broad filesystem policy rewrite, unrelated command-profile changes,
  or changing public CLI behavior without targeted tests.

## Acceptance Criteria

- [x] `FileBundleCheckpointStore` no longer uses a separator-unsafe Windows string
  prefix fallback.
- [x] Deterministic regression proves a prefix sibling such as `workspace-sibling`
  is not treated as inside `workspace` under Windows-style case-insensitive
  containment.
- [x] Checkpoint capture and restore reject escaped or prefix-sibling targets before
  writing or deleting anything.
- [x] `DeletePathTool` preserves sandbox gating and root refusal while avoiding
  independent lexical containment assumptions.
- [x] Remaining direct `startsWith` path checks in runtime/tool/safety code are
  audited and either migrated or explicitly classified as non-security
  bookkeeping.
- [x] No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `FileBundleCheckpointStoreTest` or a new containment primitive test
  for separator-safe Windows fallback.
- Unit test: checkpoint restore/capture refuses prefix-sibling targets.
- Unit test: `WorkspaceOperationToolsTest` preserves delete root refusal and
  sandbox-backed outside-workspace rejection.
- Optional audit test: a source-level contract test that forbids direct
  separator-unsafe string-prefix containment in checkpoint code.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.checkpoint.FileBundleCheckpointStoreTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.tools.impl.WorkspaceOperationToolsTest" --no-daemon
git diff --check
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Completion Evidence

- Red test run:
  `.\gradlew.bat test --tests "dev.talos.runtime.checkpoint.FileBundleCheckpointStoreTest" --no-daemon`
  failed before production changes with the two expected prefix-sibling
  assertions:
  `rejectsWindowsPrefixSiblingEscapeBeforeCapture` and
  `restoreRejectsWindowsPrefixSiblingManifestEntryBeforeWriting`.
- Implemented `dev.talos.core.security.WorkspaceContainment` with
  nearest-existing-parent canonicalization and separator-safe Windows fallback.
- Migrated checkpoint capture/restore containment from the local
  `startsWithWorkspace(...)` helper to `WorkspaceContainment.contains(...)`.
- Migrated `DeletePathTool` root/outside guard to `WorkspaceContainment` while
  preserving the existing `WorkspaceOperationToolSupport.resolveAllowed(...)`
  sandbox gate.
- Added deterministic regressions for prefix-sibling capture rejection,
  prefix-sibling restore write rejection, prefix-sibling restore delete
  rejection, and delete root refusal.
- Focused green:
  `.\gradlew.bat test --tests "dev.talos.runtime.checkpoint.FileBundleCheckpointStoreTest" --tests "dev.talos.tools.impl.WorkspaceOperationToolsTest" --no-daemon`
  passed.
- Full green:
  `.\gradlew.bat check --no-daemon` passed.

## Direct Containment Audit Notes

- `FileBundleCheckpointStore` no longer has `startsWithWorkspace(...)` or the
  separator-unsafe `toString().toLowerCase(...).startsWith(...)` fallback.
- `DeletePathTool` still performs explicit root refusal, but it now uses the
  same containment primitive after the normal sandbox-backed path resolution.
- Remaining direct `Path.startsWith(...)` calls outside this ticket include
  verifier/readback/progress helpers, command-profile loaders, slash-command
  render paths, session-id prefix logic, and protected-path classification.
  They were not mechanically replaced because several are non-security
  bookkeeping or have their own protected-path/sandbox owner. Future work should
  migrate them only with targeted red regressions.

## Work-Test Cycle Notes

- Use strict TDD. Write the checkpoint containment red test before editing
  production code.
- Keep this out of the candidate-cut path unless the owner explicitly starts a
  new candidate loop.
- Add a `CHANGELOG.md` `Unreleased` entry when implementation lands.

## Known Risks

- Over-centralizing all path checks at once could create broad behavior churn.
  Start with the confirmed checkpoint defect and the delete guard cleanup.
- Some direct `startsWith` usages are harmless identity or session-prefix checks;
  do not mechanically replace all of them without classification.

## Known Follow-Ups

- After this ticket, consider an architecture-boundary test for security-relevant
  containment checks if the primitive proves stable.
