# 05. Storage Responsibilities

This document defines **storage responsibilities** at a high level.

It does **not** choose final storage products yet.
It does **not** define schemas yet.
It does **not** define Java persistence classes yet.

The goal is to decide **what kind of truth lives where** before implementation choices are made.

---

## 1. Why this document matters

Loqs is not a normal web app.

It is a **local-first assistant platform** that must handle:
- private local sources
- workspace boundaries
- retrieval indexes
- generated artifacts
- memory
- task history
- approvals
- runtime state

Because of that, the project should not assume:
- one database for everything
- one storage abstraction for every kind of data
- one persistence strategy for both raw content and derived state

The right question is:

**What kind of data exists, and what storage role fits it best?**

---

## 2. The four storage roles

The architecture should assume four storage roles.

### A. Raw Content Storage
For original source content and generated file-based artifacts.

Examples:
- imported or referenced local files
- PDFs
- DOCX
- code repositories
- screenshots
- attachments
- converted files
- exported reports

### B. Structured State Storage
For durable structured application state.

Examples:
- workspace records
- source metadata
- task records
- step records
- approval records
- memory records
- artifact metadata
- model profile metadata
- runtime settings
- permission rules

### C. Knowledge Index Storage
For LOQ-J retrieval structures.

Examples:
- parsed chunks
- lexical index structures
- embedding-related retrieval state
- mappings between sources and retrievable units
- provenance-oriented retrieval references

### D. Transient Cache Storage
For disposable or reconstructable temporary data.

Examples:
- temporary extraction output
- preview renderings
- scratch results
- temporary page content
- temporary model intermediate outputs

---

## 3. The main architectural rule

The system should separate:
- **source truth**
- **structured operational truth**
- **knowledge index state**
- **temporary cache**

This separation matters for:
- performance
- resource discipline
- rebuildability
- clarity
- local reliability

---

## 4. Storage responsibility by core concept

## Workspace

### Durable truth
A workspace needs durable structured storage.

### Why
A workspace has identity, configuration, scope, and policies.

### Notes
A workspace may also correspond to one or more file-system locations, but workspace identity is not only a directory path.

---

## Source

A source has multiple storage aspects.

### Raw truth
The actual source content usually belongs in raw content storage.

### Structured truth
The system also needs metadata about the source, such as:
- workspace association
- source type
- format
- media type
- path or reference
- indexing state
- fingerprinting/version metadata later

### Knowledge state
A source may also be represented inside LOQ-J index storage.

### Important rule
The source itself and the knowledge index derived from it are not the same thing.

---

## Artifact

Artifacts may be:
- file-based
- metadata-only
- mixed

### Examples
- a summary text may exist as metadata and/or a saved file
- a converted document is file-based
- a comparison result may be both structured metadata and an exportable file

### Rule
Artifact content and artifact metadata should be allowed to live separately when useful.

---

## Task and Step

### Durable truth
Tasks and steps need structured durable storage when we want:
- history
- tracing
- resumability later
- operational visibility

### Important note
We do not need to decide the full trace-retention policy yet, but task/step state is clearly structured state, not raw file storage.

---

## Approval

### Durable truth
Approval requests and decisions should be durable structured state.

### Why
Approval is part of safety and auditability.

---

## Memory

### Durable truth
Memory should be durable structured state.

### Important distinction
Memory is not the same as indexed source content.

It should remain a separate concern in both architecture and storage.

---

## Evidence and Context Pack

### Usually derived state
Evidence and context packs are usually derived from sources and retrieval.

### Practical guidance
They may be:
- ephemeral only
- temporarily cached
- partially logged for diagnostics
- partially persisted for traceability later

### Important rule
Evidence is generally not the same kind of durable truth as a source or workspace.

---

## Model Profile

### Durable truth
Model profiles and runtime bindings belong in structured state.

### Why
They describe configured system behavior, not raw content.

---

## Research and Action Sessions

### Likely structured state
Research and action session metadata should be treated as structured state.

### Content handling
The temporary page/session content itself may remain transient unless explicitly saved as a source or artifact.

---

## 5. Truth ownership summary

This is the most important part of the document.

### Raw Content Storage owns
- source files
- large generated file artifacts
- imported content copies when needed

### Structured State Storage owns
- workspace identity and settings
- source metadata
- tasks and steps
- approvals
- memory
- artifact metadata
- model/runtime metadata
- policies and permissions

### Knowledge Index Storage owns
- source-derived retrievable units
- lexical/vector retrieval state
- evidence-oriented retrieval support structures

### Transient Cache Storage owns
- temporary or reconstructable working data

---

## 6. Design rules for storage

### Rule 1 — Do not duplicate large content without clear reason
If a source already exists locally, unnecessary copies should be avoided.

### Rule 2 — Structured state should remain lightweight
The structured state layer should not become a dumping ground for raw files and huge blobs.

### Rule 3 — Knowledge index state should be rebuildable
Where practical, LOQ-J index state should be treated as derived from sources, not as the primary source of truth.

### Rule 4 — Temporary state should be disposable
Transient cache should be safe to clear without destroying core truth.

### Rule 5 — Workspace boundaries should be visible in storage responsibilities
Workspaces should influence how state is organized and isolated.

### Rule 6 — Safety history should not be ephemeral
Approval-related records should not rely on transient storage.

---

## 7. What this means for later design

This storage model implies that later persistence design should likely separate:
- raw content handling
- structured state handling
- LOQ-J knowledge index handling
- transient cache handling

That is the right direction for a local assistant system.

This conclusion is more important than naming a specific database product at this stage.

---

## 8. Final storage stance

The project should be designed around a **hybrid local persistence model**.

Not because complexity is desirable.

But because the system contains fundamentally different kinds of data, and forcing them all into one persistence model would make the project harder to maintain and less efficient.
