# [T844-open-high] RAG, Vectors, And Beta Best-Practices Docs

Status: open
Priority: high
Type: docs-honesty
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Add user-facing documentation for Talos retrieval, RAG, vectors, and practical
beta usage before public beta wording hardens. This is a docs-first ticket, not
a retrieval implementation ticket.

## Scope

- Explain that RAG in Talos means the local Lucene index and retrieval
  pipeline, not cloud search or a vector database.
- Explain BM25-only retrieval and when it is the right path.
- Explain that vector retrieval requires a local embedding endpoint and falls
  back to BM25-only when embeddings are disabled or fail.
- Explain when users should prefer direct reads over retrieval.
- Document practical commands: `talos rag-index`, `talos rag-ask`,
  `/reindex`, `/status --verbose`, and `talos diagnose -q ...`.
- Document private-mode retrieval limits.
- Add beta best-practice guidance for narrow workspaces, deliberate indexing,
  cited files, diagnostics, and private-paperwork limits.
- Update stale tracked trust-limit wording so post-T834/T837 redaction claims
  describe best-effort secret-shape coverage without claiming complete secret
  or PII detection.

## Non-Goals

- No production `src/main` behavior change.
- No vector database replacement.
- No retrieval evaluation harness in this ticket.
- No model or hardware requirement claims beyond existing evidence.
- No private-paperwork product claim.

## Acceptance Criteria

- User docs include bounded RAG/vector guidance.
- Tracked public docs do not claim vector retrieval is always active.
- Tracked public docs do not claim complete secret, PII, or credential
  detection.
- `TrustClaimsHonestyTest` pins the bounded retrieval and redaction wording.
- `check --no-daemon` and `wikiEvidenceCloseGate --rerun-tasks --no-daemon`
  pass before closeout.

## Implementation State

Status: implemented-awaiting-review

Docs added:

- `docs/user/retrieval-and-vectors.md`
- `docs/user/beta-best-practices.md`

Tracked stale redaction wording was updated in README, AGENTS, and the guarded
docs surfaces.

Focused verification:

- `.\gradlew.bat test --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon`
  passed after the bounded RAG/vector and redaction guards were added.
