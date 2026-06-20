# [T838-done-high] Master Key Custody

Status: done
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
- `work-cycle-docs/research/opus-wave6-deep-research-review-20260618.md`

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

## Implementation State

Implemented in commit `7a4decca55b1dc3a1a4bbfbfbdf5c48517b046b3` and accepted
for closeout after review. The change:

- adds Windows DPAPI CurrentUser custody for `FileSecretStore` master keys;
- stores `.master.key` as a versioned DPAPI-protected blob on Windows instead
  of raw AES key bytes;
- preserves the per-entry AES-GCM format and migrates legacy raw master keys in
  place without re-encrypting `.bin` entries;
- verifies the protected blob round-trips before replacing a legacy raw key and
  leaves no persistent plaintext `.master.key.legacy` backup;
- passes key material to PowerShell DPAPI through stdin/base64, not process
  arguments or environment variables;
- fails closed on protect, unprotect, or timeout failures without silently
  writing plaintext on Windows;
- keeps non-Windows custody unchanged and documents that bounded limitation.

Report:

- `work-cycle-docs/reports/t838-master-key-custody.md`

Closeout evidence:

- Focused custody, security, and docs-honesty tests passed.
- Real Windows DPAPI tests executed on this host and passed.
- Full `check --no-daemon` passed.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed.
- Independent review verified DPAPI shell-out security, migration safety,
  fail-closed behavior, and bounded disclosure.
- `site/` was not edited or staged.

## Non-Goals

- Do not claim protection against a same-user process that can ask the OS
  keystore to decrypt on behalf of the logged-in user.
- Do not implement macOS Keychain or Linux libsecret in this Windows-first
  ticket unless explicitly scoped later.
