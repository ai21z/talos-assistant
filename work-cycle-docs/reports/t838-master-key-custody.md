# T838 Master Key Custody

Status: open for review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

T838 closes the Wave 6 high trust gap where `FileSecretStore` stored the raw
AES-256 master key beside encrypted secret entries at
`~/.talos/secrets/.master.key`.

## Implementation Summary

- Added `dev.talos.core.security.WindowsDpapiKeyCustody` as the master-key
  custody owner.
- On Windows, `.master.key` is now a versioned DPAPI CurrentUser-protected
  blob. The raw 32-byte AES key is unprotected only in JVM memory.
- Existing per-entry secret files remain AES-GCM with the same master-key bytes;
  T838 does not re-encrypt `.bin` entries.
- Legacy raw 32-byte `.master.key` files migrate in place on Windows:
  - protect the same raw bytes with DPAPI;
  - write a protected temp blob;
  - verify DPAPI round-trip before replacement;
  - atomically replace `.master.key`;
  - leave no persistent plaintext `.master.key.legacy` backup.
- DPAPI subprocess input uses stdin/base64. Key material is not passed on the
  command line or in environment variables.
- DPAPI failures are fail-closed: no plaintext write on Windows new-key
  creation, no replacement on failed migration, and no overwrite on failed
  unprotect.
- Non-Windows behavior remains unchanged in this ticket: raw-key custody is not
  yet OS-backed off Windows.
- `FileSecretStore` now loads the master key lazily so slash-command
  registration and missing delete/get paths do not create key material.

## Boundaries

T838 does not claim hardware-backed custody, tamper-proof storage, protection
against same-user processes that can ask Windows to unprotect DPAPI data, or
cross-platform OS-backed custody.

The shipped claim is bounded:

> On Windows, the local secret-store master key is protected at rest with DPAPI
> CurrentUser and is tied to the Windows user account. This is not
> hardware-backed custody and does not protect against a same-user process that
> can ask Windows to unprotect it. On non-Windows platforms, master-key custody
> remains unchanged and is not yet OS-backed.

## Tests Added Or Extended

- `FileSecretStoreMasterKeyCustodyTest`
  - missing delete does not create `.master.key`;
  - fresh Windows store writes a versioned DPAPI blob, not legacy raw key bytes;
  - protected master keys reopen and decrypt existing entries;
  - legacy raw Windows master keys migrate without re-encrypting entries or
    leaving a plaintext backup.
- `WindowsDpapiKeyCustodyTest`
  - protect failure during new-key creation writes no plaintext master key;
  - protect failure during legacy migration leaves the original raw key
    untouched for explicit recovery;
  - unprotect failure for an existing protected blob does not overwrite it.
- `TrustClaimsHonestyTest`
  - pins the bounded Windows-only master-key custody disclosure.

## Non-Claims

- T838 does not implement macOS Keychain or Linux libsecret.
- T838 does not touch provider authentication or runtime model-engine paths.
- T838 does not touch `SetupCmd.java`.
- T838 does not overwrite `~/.talos/config.yaml`.
- T838 does not edit `site/`.
- T838 remains open for review.
