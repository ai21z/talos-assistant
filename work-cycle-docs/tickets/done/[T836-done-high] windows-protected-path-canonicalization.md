# [T836-done-high] Windows Protected-Path Canonicalization

Status: done
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Implementation state: done

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

## Implementation Record

- Report: `work-cycle-docs/reports/t836-windows-protected-path-canonicalization.md`
- Implementation commit: `bbab3bcd53c505d74160ace66cbe852eb2893509`
- `ProtectedPathTokens` now canonicalizes trailing dots and spaces per segment
  before protected-token matching.
- `ProtectedWorkspacePaths.POLICY_VERSION` is bumped to
  `protected-content-policy-v5`.
- Runtime `ProtectedPathPolicy` remains aligned through the existing delegate.
- Public docs now use the bounded current behavior wording:
  "Windows trailing-dot and trailing-space path aliases are canonicalized before
  protected-path matching; this is not a complete Windows path-security proof."
- Review accepted after focused safety/docs tests, runtime/privacy/architecture
  focused tests, full `check --no-daemon`,
  `wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and
  `git diff --check HEAD^ HEAD -- . ':!site'` passed.
