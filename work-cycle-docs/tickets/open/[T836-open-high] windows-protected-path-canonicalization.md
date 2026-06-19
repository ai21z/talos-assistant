# [T836-open-high] Windows Protected-Path Canonicalization

Status: open
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Close the Wave 6 high trust gap where Windows path variants that differ only by
trailing dots or spaces can bypass exact-name protected-path matching.

Source context:

- `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`
- `work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md`

## Scope

- Canonicalize Windows path segments before protected-token matching.
- Normalize trailing dots and spaces per segment.
- Reject or protect reserved device names and other Windows aliases that can
  reach a different file than the literal path suggests.
- Classify the OS-resolved real target where it exists.

## Acceptance Criteria

- Tests cover `id_rsa.`, `id_rsa `, `.ssh.`, `secrets.`, and trailing-space
  variants.
- Exact private-key filenames and secret directory segments remain protected
  after Windows canonicalization.
- Existing workspace-boundary protections remain intact.
- User-facing docs can remove the T833 Windows trailing-dot/trailing-space
  caveat only after the tests prove the fix.

## Non-Goals

- Do not redesign the permission model.
- Do not claim complete cross-platform path security beyond the tested
  canonicalization and sandbox boundaries.
