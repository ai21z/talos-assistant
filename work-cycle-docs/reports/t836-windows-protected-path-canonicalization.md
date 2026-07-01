# T836 Windows Protected-Path Canonicalization

Status: done
Branch: `v0.9.0-beta-dev`
Base commit: `7f7a4560c9650587d39bdd0b522106e65c28b19d`
Initial implementation commit: `bbab3bcd53c505d74160ace66cbe852eb2893509`
Premature closeout commit: `ed8d0230775e379a140a7d969015c01e82d9c789`
NTFS 8.3 follow-up commit: `56e2243569ce9b5329cb44c1bfcb6169e9bb54b1`
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
  `protected-content-policy-v6`, forcing stale RAG privacy partitions to rebuild
  under the new classifier.
- `ProtectedWorkspacePaths` prefers the canonical alias target when the literal
  spelling does not exist and the canonical target does, while still relying on
  `ProtectedPathTokens` for fail-closed classification of literal alias
  spellings.
- `ProtectedWorkspacePaths` classifies existing targets and nearest existing
  ancestors by OS real path. On Windows this expands NTFS 8.3 aliases such as
  `SSH~1/mykey` to their long-name protected target (`.ssh/mykey`) before
  token matching.
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
- NTFS 8.3 short-name aliases such as `SSH~1/mykey`, `AWS~1/config`, and
  `AZURE~1/profile.json` when the filesystem exposes those aliases.
- New files under a short-name protected directory such as `SSH~1/new-key.txt`.
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

## Reopen Finding

The first T836 closeout was premature. Post-closeout review reproduced an NTFS
8.3 short-name bypass on this Windows host:

- `.ssh` resolved through `SSH~1`, `.aws` through `AWS~1`, and `.azure` through
  `AZURE~1`.
- Java could read the real protected content through the short-name alias.
- The closed T836 implementation classified `SSH~1/mykey`,
  `AWS~1/config`, and `AZURE~1/profile.json` as unprotected in-workspace paths.

T836 was reopened until the 8.3 follow-up fix could be reviewed and closed.

## Closeout Evidence For Initial Implementation

- Focused safety/docs tests passed:
  `ProtectedPathTokensTest`, `ProtectedWorkspacePathsTest`, and
  `TrustClaimsHonestyTest`.
- Runtime/privacy/architecture focused tests passed:
  `ProtectedReadScopeIntegrationTest`, `dev.talos.runtime.policy.*`,
  `IndexerPolicyMetadataTest`, and `LayeredArchitectureTest`.
- Full `check --no-daemon` passed.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed.
- `git diff --check HEAD^ HEAD -- . ':!site'` passed.

## Closeout Evidence For NTFS 8.3 Follow-Up

- The regression first failed on the closed implementation with
  `protectedPath=false` for an NTFS short-name protected-directory alias.
- The regression now runs on this Windows host and passes for
  `SSH~1/mykey`, `AWS~1/config`, `AZURE~1/profile.json`, and
  `SSH~1/new-key.txt`.
- A direct post-fix probe confirmed `SSH~1/mykey` and `SSH~1/new-key.txt`
  classify as `.ssh/...`, `protectedPath=true`, `protectedKind=SECRET`.
- Focused safety/docs tests passed:
  `ProtectedPathTokensTest`, `ProtectedWorkspacePathsTest`, and
  `TrustClaimsHonestyTest`.
- Runtime/privacy/architecture focused tests passed:
  `ProtectedReadScopeIntegrationTest`, `dev.talos.runtime.policy.*`,
  `IndexerPolicyMetadataTest`, and `LayeredArchitectureTest`.
- Full `check --no-daemon` passed.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed.
