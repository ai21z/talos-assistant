# [T840-open-high] Protected Path Realpath-Failure Fail-Closed

Status: open
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Close the narrow T836 residual where
`ProtectedWorkspacePaths.realPathForClassification(...)` falls back to a
lexical normalized path when `toRealPath()` fails. If a Windows 8.3-style
short-name segment survives that fallback, the protected-path classifier can
miss a protected target and treat the path as an ordinary workspace path.

## Source Context

- `src/main/java/dev/talos/safety/ProtectedWorkspacePaths.java`
- `src/test/java/dev/talos/safety/ProtectedWorkspacePathsTest.java`
- `work-cycle-docs/tickets/done/[T836-done-high] windows-protected-path-canonicalization.md`

## Scope

- Add a safety-owned post-resolution guard for unresolved DOS/NTFS 8.3-style
  short-name segments after workspace-relative path calculation.
- If an unresolved short-name segment remains inside the workspace, classify it
  fail-closed as protected `CONTROL` rather than treating it as ordinary
  content.
- Apply the same fail-closed behavior to `isProtectedPath(...)`.
- Keep successful realpath expansion behavior unchanged so genuine aliases like
  `SSH~1` still classify by their real `.ssh` target when the filesystem
  resolves them.
- Bump `ProtectedWorkspacePaths.POLICY_VERSION` so stale protected-content
  privacy partitions rebuild.

## Acceptance Criteria

- Portable tests prove unresolved short-name segments such as `SSH~1` fail
  closed without requiring real NTFS short-name support.
- Existing Windows-gated NTFS 8.3 alias regression remains green.
- Ordinary safe paths and non-8.3 tilde names such as `notes~draft.md` remain
  non-protected.
- `ProtectedPathPolicy` continues to match the direct safety classifier.
- Focused safety/runtime tests, full `check --no-daemon`,
  `wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and diff hygiene pass.
- `site/` is not edited or staged.

## Implementation Review State

Implementation has landed for review in the current branch. The change:

- bumps `ProtectedWorkspacePaths.POLICY_VERSION` to
  `protected-content-policy-v7`;
- classifies unresolved Windows 8.3-style short-name segments inside the
  workspace as protected `CONTROL` paths, including surviving short-name
  shapes that contain `_`, `$`, `@`, or `-`;
- makes `isProtectedPath(...)` fail closed on the same unresolved short-name
  shape;
- preserves existing workspace-escape behavior before the short-name guard;
- keeps successful realpath expansion behavior unchanged for real NTFS aliases;
- leaves ordinary safe paths and non-8.3 tilde names non-protected.

T840 remains open until review verifies the fail-closed behavior and gate
results.

## Non-Goals

- Do not modify `ProtectedPathTokens` vocabulary unless the classifier cannot
  be fixed locally.
- Do not change workspace-escape semantics for paths that are actually outside
  the workspace.
- Do not recut a candidate, change Qodana policy, touch `SetupCmd.java`, or
  edit `site/`.
