# T759 - Single Protected-Path Classifier With Equals-Or-Suffix Matching

Status: done - completed in wave 2; see completion evidence section
Severity: high
Release gate: yes (safety classifier consolidation + false-positive fix)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: external assistant

## Problem

Protected-path classification existed in FIVE divergent copies: the
canonical `safety/ProtectedPathTokens` (substring matching, used by
permission policy, EvidenceGate, Indexer, TraceRedactor, SafeLogFormatter)
plus four byte-identical planner-local `isSensitiveReadbackPath` methods
(SourceDerivedEvidenceGuard, ExpectedTargetScopeRepairPlanner,
TargetReadbackCompactRepairPlanner, CompactMutationContinuationPlanner -
blank→true fail-closed, narrower vocabulary) plus
`ProtectedReadAnswerGuard.looksProtectedPathHint` (narrowest copy). The
canonical classifier itself false-positived on derivational suffixes:
`tokenizer.java` (contains "token"), `secretary-notes.md`,
`passwordless-ssh.md`, `credentialing.md` - protected-read approval
friction and over-redaction on ordinary source files (2026-06-10
evaluation, roadmap item W2.4).

## Design (owner-approved)

Equals-or-suffix matching on lowercase LETTER RUNS (digits/_/-/. separate
runs): a run that ends with a vocabulary stem (secret(s), token(s),
credential(s), password(s), privatekey) is secret-bearing; the
(private, key) adjacent-run pair replaces contains("private_key"). Why not
the roadmap-literal pure word-boundary: secret names overwhelmingly END
with the noun (api_token, mysecrets, supersecret) - word-equality would
fail OPEN on them; suffix matching keeps them while freeing the
derivational-suffix names where the stem is a prefix of the run. "key"
alone is deliberately not a stem (monkey, keyboard). All exact-segment,
extension, and key-filename rules unchanged.

## Behavioral deltas (the characterization-test diff documents these)

Loses protection (intended false-positive fixes): tokenizer.java,
tokenize.rs, untokenized_data.csv, tokenization-notes.md,
secretary-notes.md, secretariat.txt, passwordless-ssh.md, credentialing.md.

Keeps protection: .env*, secrets/, tokens/, credentials/, protected/,
.ssh/.aws/.azure/.git/.gnupg/.github/workflows/.config/gcloud, id_rsa,
*.pem/.key/.p12/.pfx, api_token.txt, auth-tokens.json, mysecrets.txt,
supersecret.conf, AccessToken.java, password123.txt, private_key.txt,
my_tokens.txt.

Known remaining limitation (documented): lexer source files literally
named Token.java/JsonToken.java stay protected - a source-extension
exemption is a separate policy decision, out of scope.

Readback-sensitivity expansion (fail-closed, intended): the four planner
copies' narrow vocabulary (.env/.git/.ssh/.gnupg/id_rsa/credentials/secret)
widens to the full canonical vocabulary - compact repair frames inline
less content for e.g. passwords.txt/*.pem targets.

looksProtectedPathHint delta: CONTROL-kind hints (.git, .gnupg) now count
as protected hints - consistent with the adjacent workspace-resolved
branch which already included CONTROL.

## Architecture Metadata

Capability: protected-path classification (safety layer)
Operation(s): read/write gating, redaction, indexing, repair planning
Owning package/class: `dev.talos.safety.ProtectedPathTokens` (single
owner; new public `isSensitiveReadbackPath`), `ProtectedWorkspacePaths.
POLICY_VERSION` bumped v2→v3 (Indexer checks it for freshness - stale RAG
privacy partitions rebuild)
New or changed tools: none
Out of scope (recorded): SensitiveWorkspaceDetector (distinct personal-
data vocabulary), ProjectMemoryPolicy (user-prose intent), MutationIntent/
TaskContractResolver .env request parsing, ATE grounding markers (T762)
Refactor scope: the named files; five copies become delegations

## Tests / Evidence

- `ProtectedPathTokensTest`: characterization commit pinned CURRENT
  substring behavior first; the consolidation commit flips exactly the
  delta rows (diff = delta documentation). Exact-rule families, secret-
  bearing names, ordinary names, known limitation, readback fail-closed
  matrix, SafeLogFormatter consumer pins (api_token.txt redacted to
  <protected-path>; tokenizer.java passes through).
- `ProtectedPathPolicyTest`: derivational-suffix paths unprotected at the
  policy level; api_token/mysecrets still protected.
- Full unit + e2e suites green (no existing test pinned the false
  positives; protected-read scenarios 65/66/70 unchanged).

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green.
- Five copies now delegate to the canonical classifier; POLICY_VERSION v3.
