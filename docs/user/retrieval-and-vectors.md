# Retrieval And Vectors

This page answers: "When should I use RAG, vectors, direct reads, or
diagnostics?"

## What RAG Means In Talos

RAG in Talos means the local Lucene index and retrieval pipeline, not cloud
search or a vector database.

The index lives under the Talos local data area for the selected workspace.
Talos uses it to find likely relevant snippets, then cites the local files that
provided the evidence.

Retrieval is evidence, not permission to inspect everything. Protected paths,
unsupported file families, private-mode settings, and policy metadata still
govern what can enter an index or model context.

## BM25

BM25 is lexical retrieval. It works without embeddings.

Use BM25-oriented retrieval when the question contains:

- exact file names
- paths
- symbols
- identifiers
- config keys
- error messages
- obvious text matches

BM25 is the minimum practical retrieval path for beta use. If embeddings are
disabled or unavailable, Talos can still use BM25-only retrieval.

## Vectors

Vector retrieval requires a local embedding endpoint. When embeddings are
disabled or fail, Talos falls back to BM25-only retrieval.

Vectors help with semantic or fuzzy questions where the relevant file may not
share the same words as the question. They do not replace direct reads and they
do not make retrieval complete.

The shipped beta default is BM25-only:

```yaml
embed:
  provider: "disabled"
  model: "none"
  host: ""
  allow_remote: false

rag:
  vectors:
    enabled: false
```

An explicit local embedding endpoint uses keys such as:

```yaml
embed:
  provider: "compat"
  model: "<local-embedding-model>"
  host: "http://127.0.0.1:<port>"
  allow_remote: false

rag:
  vectors:
    enabled: true
```

`embed.provider: "disabled"` or `rag.vectors.enabled: false` means BM25-only
retrieval. Remote embedding hosts are rejected unless `embed.allow_remote` is
explicitly enabled.

## Hybrid Retrieval

When vectors are enabled and a local embedding endpoint returns usable vectors,
Talos can use hybrid retrieval:

1. BM25 lexical search.
2. KNN vector search.
3. Reciprocal rank fusion.
4. Source/path boost.
5. Reranking.
6. Deduplication.

If embedding fails, the vector lane is skipped and the lexical lane still runs.
Do not assume the vector lane is active.

## Direct Reads Versus Retrieval

Prefer direct reads when you need:

- exact file contents
- line-sensitive reasoning
- an edit plan
- a verification step
- a small known target file
- proof that a specific file contains or does not contain text

Use RAG when you need:

- broad discovery
- likely files for an architecture question
- related context across a workspace
- a starting point before direct reads

When asking architecture questions, ask Talos to cite the files it used. Treat
retrieval output as a lead list, then ask for direct reads when exactness
matters.

## Commands

Build or refresh the local index:

```powershell
talos rag-index
```

Ask through the retrieval lane:

```powershell
talos rag-ask "Which files define command handling?"
```

Diagnose retrieval behavior:

```powershell
talos diagnose -q "Which files define command handling?"
talos diagnose --mode rag -q "Which files define command handling?"
```

Inside the REPL:

```text
/reindex
/reindex --stats
/status --verbose
```

## Private Mode

Private mode disables RAG/retrieve by default unless explicitly enabled.

This avoids turning protected or extracted private content into a searchable
corpus without deliberate review. If you enable private-mode retrieval, first
confirm that protected and unsupported files stay outside the searchable corpus
for your workspace and policy settings.

## Limits

Talos does not yet claim measured workspace-intelligence quality across a broad
corpus. Retrieval quality still needs a gold-context benchmark with expected
files, symbols, line ranges, related tests, and junk-context metrics.

Do not use beta Talos as a private-paperwork search engine. That is a separate
privacy and evidence problem.
