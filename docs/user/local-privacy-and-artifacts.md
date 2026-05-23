# Local Privacy And Artifacts

This page answers: "What data can Talos read, send to model context, or persist
locally?"

## Current Support

Talos is local-first, but local-first does not mean "nothing is ever captured."
Talos can create local runtime artifacts such as traces, prompt-debug captures,
provider-body captures, session state, command logs, indexes, and cache files.

Use private mode for sensitive workspaces:

```text
/privacy status
/privacy private on
```

## Developer Mode

Developer/default mode is designed for normal code and text workspaces.

In this mode, approved direct protected reads may enter model context for the
current turn.

Do not use developer mode for private paperwork folders.

## Private Mode

Private mode changes protected-read and document-extraction handling.

In private mode:

- approved protected reads default to local-display-only
- extracted PDF/DOCX/XLS/XLSX text is local-display-only by default
- RAG/retrieve is disabled by default
- raw protected/document content persistence is off by default

Operational traces, prompt-debug captures, provider-body captures, sessions,
logs, and command output may still exist locally. Private mode narrows sensitive
content handoff; it is not a guarantee that no local operational artifacts are
created.

Private mode does not make Talos ready for tax, health, legal, family, or
administrative paperwork.

## Protected Reads

A protected read can be approved or denied. Denial is expected not to reveal
protected content.

When approved in private mode, the content is withheld from model context unless
separate config opt-ins allow otherwise.

## RAG And Indexing

RAG indexing is disabled by default in private mode. This avoids placing
protected or unsupported content into a searchable corpus without explicit
review.

## Local Artifact Types

Talos may write local artifacts for:

- turn traces
- prompt-debug evidence
- provider-body captures
- session storage
- command output
- model/cache configuration
- RAG indexes

Treat these artifacts as local evidence. Do not publish them without review.

## Good Beta Use

Good:

- code projects
- Markdown/text workspaces
- config files
- static web projects
- controlled test fixtures

Not a beta claim:

- private personal paperwork folders
- legal or medical records
- broad home directories
- folders full of secrets
