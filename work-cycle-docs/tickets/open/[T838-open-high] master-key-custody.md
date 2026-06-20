# [T838-open-high] Master Key Custody

Status: open
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Close the Wave 6 high trust gap where the local master key is stored beside the
encrypted data, making current encryption casual-inspection protection rather
than OS-backed key custody.

Source context:

- `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`
- `work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md`

## Scope

- Implement Windows-first OS-backed wrapping for the local master key using
  DPAPI, Credential Manager, or an equivalent platform-backed mechanism.
- Ensure the raw AES master key is not stored beside ciphertext.
- Keep current docs bounded until macOS and Linux key custody are implemented
  and tested.

## Acceptance Criteria

- Windows storage no longer writes the raw AES master key next to encrypted
  secret data.
- Tests prove existing encrypted data can be read or migrated according to the
  chosen compatibility policy.
- Failure modes are explicit: unavailable OS custody must fail clearly or fall
  back only with an honest warning and documented reduced protection.
- Public docs do not claim cross-platform OS-backed custody until macOS and
  Linux implementations exist.

## Implementation Review State

Implementation has landed for review with report:

- `work-cycle-docs/reports/t838-master-key-custody.md`

T838 remains open until the DPAPI shell-out, migration safety, fail-closed
behavior, and bounded disclosure are reviewed.

## Non-Goals

- Do not claim protection against a same-user process that can ask the OS
  keystore to decrypt on behalf of the logged-in user.
- Do not implement macOS Keychain or Linux libsecret in this Windows-first
  ticket unless explicitly scoped later.
