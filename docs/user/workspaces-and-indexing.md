# Workspaces And Indexing

This page answers: "What does Talos inspect, and how does indexing affect
answers?"

## Current Support

Talos works against a selected local workspace. The workspace is the boundary
for ordinary file inspection and governed changes.

Start in the current directory:

```powershell
talos
```

Start with an explicit workspace:

```powershell
talos run --root C:/path/to/workspace
```

Check workspace state:

```text
/workspace
/status
/status --verbose
```

## Workspace Boundary

Talos is designed around local workspace scope:

- read and list operations are expected to stay inside the selected workspace
- mutations are governed and approval-gated
- command execution uses configured profiles
- protected paths have stricter handling

Do not start Talos in a broad personal folder if the task only concerns one
project. Start it in the project directory.

## Index State

The startup banner shows a compact index state. Current snapshot states include:

- not indexed
- ready, with chunk count when available
- unavailable, when Talos cannot read the index

Reindexing and first retrieval can also print live indexing progress. An index
helps retrieval-oriented answers. It is not a license to inspect everything.
Protected and unsupported files remain governed by policy.

## Reindex

Inside the REPL:

```text
/reindex
/reindex --stats
/reindex --full
/reindex --prune [days]
```

Use reindexing when workspace contents changed and retrieval answers appear
stale.

## Retrieval And Direct Reads

Retrieval and direct file reads are different:

- retrieval uses the index and snippets
- direct reads inspect specific files through tools
- protected content may require approval before direct inspection
- private mode changes how protected or extracted content can enter model
  context

## Included And Excluded File Families

Default indexing includes Markdown/text, Java/Kotlin/Gradle files, XML,
YAML, JSON, CSV/TSV, properties, HTML/HTM, selected extractable document
formats, and image extensions. Image text extraction is disabled by default and
is not a beta product claim.

Default indexing excludes protected locations, build outputs, dependency
folders, archives, compiled binaries, legacy document types, and presentation
files.

Direct file reads are separate from indexing. Talos can inspect an approved
workspace text file even when that extension is not part of the default index
include list.

Use [File Support](file-support.md) for exact capability boundaries.
