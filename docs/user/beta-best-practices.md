# Beta Best Practices

This page answers: "How should I use beta Talos so the trust boundaries stay
useful?"

## Start Narrow

Start Talos in the project directory you actually want to work on.

Good:

```powershell
cd C:/work/my-project
talos
```

Avoid starting Talos in a broad home folder. A narrower workspace makes file
inspection, indexing, approval prompts, and traces easier to review.

## Index Deliberately

Index only workspaces where local indexing is acceptable.

Use:

```powershell
talos rag-index
```

or inside the REPL:

```text
/reindex
```

Do not index folders full of private paperwork, secrets, legal documents,
medical documents, or broad personal archives in the beta.

## Choose The Right Evidence Path

Use RAG for broad discovery:

- "Which files define command handling?"
- "Where is indexing configured?"
- "Which tests cover protected paths?"

Use direct reads for exact facts:

- "Read README.md and summarize the install path."
- "Read src/main/java/... and explain this method."
- "Verify whether this exact line exists."

Use edits only after reviewing the proposed diff. Talos should ask before it
writes.

## Ask For Citations

For architecture or workspace questions, ask for cited files.

Example:

```text
Explain how retrieval works. Cite the files you used.
```

If the answer looks broad or uncertain, ask Talos to read the cited files
directly before making an edit.

## Check Status When Retrieval Feels Weak

Start with:

```powershell
talos status --verbose
talos diagnose -q "Why did retrieval miss the command files?"
```

Inside the REPL:

```text
/status --verbose
/reindex --stats
```

Useful things to check:

- whether the workspace is indexed
- whether vectors are enabled or disabled
- whether an embedding endpoint is configured
- whether retrieval is in BM25-only fallback
- whether private mode disables RAG/retrieve

## Keep Models Local Unless You Mean Otherwise

Chat and embedding hosts are localhost-gated by default. Keep them on
`127.0.0.1`, `::1`, or `localhost` for the local-first beta path.

Only enable remote hosts when you understand that prompts or embedding inputs
can leave the machine.

## Treat Redaction As Best-Effort

Secret redaction is best-effort. It covers common key=value secret shapes,
known canaries, common standalone token prefixes, AWS access-key shapes,
JWT-like tokens, PEM private-key blocks, and URL/connection-string userinfo in
model-context and durable sinks. Command-output handoff also withholds bounded
high-entropy command streams before model context.

This is not exhaustive secret, PII, or credential detection. Do not paste real
secrets into prompts or ask commands to print credentials.

## Stay Inside The Beta Boundary

Good beta work:

- code projects
- Markdown and text projects
- JSON, YAML, TOML, XML, CSV, and config files
- text-bearing PDFs, DOCX, and Excel workbooks when extraction limits are
  acceptable
- static websites and source assets

Not a beta claim:

- private paperwork folders
- tax, health, legal, family, or administrative archives
- PowerPoint workflows
- image OCR workflows
- layout-perfect document review
- browser or web-research automation

## When In Doubt

Use the narrowest workspace, ask for citations, read exact files before edits,
and run verification before accepting a completion claim.
