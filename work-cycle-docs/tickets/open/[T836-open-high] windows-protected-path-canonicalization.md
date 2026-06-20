# [T836-open-high] Windows Protected-Path Canonicalization

Status: open
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Implementation state: reopened after review; NTFS 8.3 follow-up implemented, open for review

## Purpose

Close the Wave 6 high trust gap where Windows path variants that differ only by
trailing dots or spaces can bypass exact-name protected-path matching.

Source context:

- `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`
- `work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md`

## Scope

- Canonicalize Windows path segments before protected-token matching.
- Normalize trailing dots and spaces per segment.
- Reject or protect reserved device names and other Windows aliases that can
  reach a different file than the literal path suggests.
- Classify the OS-resolved real target where it exists.

## Acceptance Criteria

- Tests cover `id_rsa.`, `id_rsa `, `.ssh.`, `secrets.`, trailing-space
  variants, and NTFS 8.3 short-name aliases such as `SSH~1/mykey`,
  `AWS~1/config`, and `AZURE~1/profile.json` when the filesystem exposes
  those aliases.
- Exact private-key filenames and secret directory segments remain protected
  after Windows canonicalization.
- Existing workspace-boundary protections remain intact.
- User-facing docs can remove the T833 Windows trailing-dot/trailing-space
  caveat only after the tests prove the fix.

## Non-Goals

- Do not redesign the permission model.
- Do not claim complete cross-platform path security beyond the tested
  canonicalization and sandbox boundaries.

## Implementation Record

- Report: `work-cycle-docs/reports/t836-windows-protected-path-canonicalization.md`
- Initial implementation commit: `bbab3bcd53c505d74160ace66cbe852eb2893509`
- Reopen finding: review of the closed ticket reproduced an NTFS 8.3 short-name
  bypass on this host. `SSH~1/mykey`, `AWS~1/config`, and
  `AZURE~1/profile.json` reached `.ssh`, `.aws`, and `.azure` content while
  classifying as ordinary in-workspace paths.
- `ProtectedPathTokens` now canonicalizes trailing dots and spaces per segment
  before protected-token matching.
- `ProtectedWorkspacePaths.POLICY_VERSION` is bumped to
  `protected-content-policy-v6`.
- `ProtectedWorkspacePaths` now classifies existing targets and nearest
  existing ancestors by OS real path, so NTFS short-name aliases resolve to
  their protected long-name directories before token matching.
- Runtime `ProtectedPathPolicy` remains aligned through the existing delegate.
- Public docs now use the bounded current behavior wording:
  "Windows trailing-dot and trailing-space path aliases are canonicalized before
  protected-path matching; this is not a complete Windows path-security proof."
- The reopened implementation remains open for review/closeout.
