# T836 Windows Protected-Path Canonicalization

Status: implemented, open for review
Branch: `v0.9.0-beta-dev`
Base commit: `7f7a4560c9650587d39bdd0b522106e65c28b19d`
Talos version: `0.10.5`

## Purpose

T836 closes the Wave 6 high trust gap where Windows path spellings that differ
only by trailing dots or trailing spaces could bypass exact-name protected-path
matching. The fix is intentionally located in the safety layer because
protected-token matching is shared by permission policy, runtime path policy,
redaction, index privacy partitioning, and repair/readback sensitivity.

## Implementation

- `ProtectedPathTokens` canonicalizes trailing dots and spaces per path segment
  before protected-token matching.
- `ProtectedPathTokens` treats Windows reserved device names (`CON`, `PRN`,
  `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`, including extension aliases such
  as `nul.txt`) as `CONTROL`.
- `ProtectedWorkspacePaths` bumps the protected-path policy version to
  `protected-content-policy-v5`, forcing stale RAG privacy partitions to rebuild
  under the new classifier.
- `ProtectedWorkspacePaths` prefers the canonical alias target when the literal
  spelling does not exist and the canonical target does, while still relying on
  `ProtectedPathTokens` for fail-closed classification of literal alias
  spellings.
- Runtime `ProtectedPathPolicy` remains a delegate to
  `ProtectedWorkspacePaths`, so direct safety classification and runtime
  resource classification stay aligned.

## Tests

New and updated tests cover:

- `id_rsa.`, `id_rsa `, and `id_rsa. `.
- `.ssh./config` and `.ssh /config`.
- `secrets./api.txt` and `secrets /api.txt`.
- `keys/server.pem.`.
- `.git./config` and `.github./workflows/ci.yml`.
- Reserved device aliases such as `con`, `nul.txt`, `aux.`, `com1.log`, and
  `lpt9 `.
- Runtime policy parity between `ProtectedWorkspacePaths` and
  `ProtectedPathPolicy`.

## Documentation Update

The T833 Windows trailing-dot/trailing-space caveat is replaced on public
Talos-owned documentation surfaces with bounded current behavior:

`Windows trailing-dot and trailing-space path aliases are canonicalized before protected-path matching; this is not a complete Windows path-security proof.`

This keeps the docs honest after the fix without claiming complete
cross-platform path security.

## Non-Claims

T836 does not redesign the permission model, prove complete Windows path
security, implement full NT namespace handling, or close unrelated protected
path edge cases such as Unicode/homoglyph tricks. Remaining Wave 6 high trust
work stays scoped to T837 (`run_command` output handoff) and T838 (master-key
custody).
