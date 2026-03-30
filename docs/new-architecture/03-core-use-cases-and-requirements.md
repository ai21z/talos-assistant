# 03. Core Use Cases and Requirements

This document captures the first stable set of project-driving use cases.

The goal is not to model every future feature.
The goal is to define the user goals that should shape the architecture.

---

# Part A. Core use cases

## UC1 — Summarize one or more sources

### Goal
The user wants a clear summary of selected or discovered sources.

### Examples
- summarize this PDF
- summarize these notes
- summarize the important parts of this repo documentation

### Main system needs
- locate sources in a workspace
- parse and read them
- retrieve relevant evidence
- generate an understandable summary
- preserve provenance when useful

---

## UC2 — Find a specific fact in one or more sources

### Goal
The user wants an exact answer grounded in local knowledge.

### Examples
- find the termination clause
- what date is mentioned in this contract
- where is the auth configuration defined

### Main system needs
- search within workspace-scoped knowledge
- return evidence and source location
- avoid unsupported guessing

---

## UC3 — Compare one or more sources

### Goal
The user wants differences, similarities, or grouping across multiple sources.

### Examples
- compare these two contracts
- compare three offer documents
- compare these implementation files

### Main system needs
- support comparison of one-to-many and many-to-many source sets
- understand different source types and formats
- produce a clear comparison artifact

---

## UC4 — Explain a coding workspace or code source set

### Goal
The user wants Loqs to help understand a codebase or technical source collection.

### Examples
- explain the auth flow in this project
- summarize repository structure
- show how these services relate

### Main system needs
- treat code as a kind of source
- retrieve evidence from repositories and files
- explain structure, behavior, and relationships clearly

---

## UC5 — Teach a topic from selected materials

### Goal
The user wants guided learning from chosen sources.

### Examples
- teach me Docker from these notes
- explain this architecture simply
- make a study path from these materials

### Main system needs
- ingest multiple source types
- adapt explanation level
- create learning artifacts such as summaries, plans, or lessons

---

## UC6 — Draft writing using workspace context

### Goal
The user wants help writing from evidence and context.

### Examples
- draft a reply using these sources
- rewrite this in a clearer tone
- produce a summary email from project context

### Main system needs
- retrieve relevant workspace evidence
- preserve user intent and style preferences
- produce artifacts that are reviewable before sending

---

## UC7 — Search the web in research mode

### Goal
The user wants the assistant to search and summarize external web information.

### Examples
- research this topic
- compare these links
- give me a short briefing from the web

### Main system needs
- separate research mode from action mode
- keep web results distinct from local workspace knowledge
- summarize and compare sources clearly

---

## UC8 — Perform a sensitive action in action mode

### Goal
The user wants the assistant to help perform a real-world action safely.

### Examples
- prepare a booking
- fill a form
- upload a selected file
- confirm an appointment flow

### Main system needs
- support browser or action workflows
- isolate workspace and permission scope
- require approval before sensitive completion

---

## UC9 — Give a daily or workspace briefing

### Goal
The user wants a concise view of what matters right now.

### Examples
- what matters today
- summarize pending admin tasks
- briefing for this workspace

### Main system needs
- gather relevant evidence from selected scopes
- combine local and optionally external information
- produce concise prioritized output

---

## UC10 — Manage work through workspace boundaries

### Goal
The user wants different domains of life and work to remain separated.

### Examples
- work workspace
- coding workspace
- learning workspace
- shopping workspace
- appointments workspace

### Main system needs
- isolate context
- isolate permissions
- isolate memory
- isolate retrieval/index scope

---

# Part B. Initial functional requirements

## FR1 — Workspace management
The system shall support isolated workspaces as the main unit of operating context.

## FR2 — Source registration and understanding
The system shall be able to register, classify, and read sources within a workspace.

## FR3 — Source classification
The system shall distinguish at least:
- source type
- format
- media type

## FR4 — Local knowledge indexing
LOQ-J shall support indexing workspace-scoped sources for retrieval.

## FR5 — Evidence retrieval
The system shall retrieve evidence relevant to a task or question.

## FR6 — Context assembly
LOQ-J shall assemble context packs from evidence for downstream use.

## FR7 — Artifact generation
The system shall produce artifacts such as summaries, comparisons, drafts, and lessons.

## FR8 — Task execution
The system shall execute user tasks through one or more steps.

## FR9 — Research mode
The system shall support read-oriented external research workflows.

## FR10 — Action mode
The system shall support controlled execution workflows distinct from research mode.

## FR11 — Approval model
The system shall request explicit approval before sensitive actions are completed.

## FR12 — Coding support
The system shall treat code and repositories as sources that can be indexed, explained, and used as context.

## FR13 — Learning support
The system shall support explanation and learning workflows based on selected sources.

## FR14 — Memory support
The system shall support memory as a separate concern from indexed source content.

## FR15 — CLI-first operation
The system shall remain usable and understandable through a command-line interface.

---

# Part C. Initial non-functional requirements

## NFR1 — Local-first
Private data should remain local by default.

## NFR2 — Resource discipline
The system should be efficient enough for local operation without unnecessary background cost.

## NFR3 — Workspace isolation
Retrieval, memory, and actions should respect workspace boundaries.

## NFR4 — Explainability
The system should show evidence/provenance when a task depends on source retrieval.

## NFR5 — Safety
Risky actions should be explicit, reviewable, and approval-gated.

## NFR6 — Modularity
The architecture should remain understandable as clear subsystems rather than a single blended blob.

## NFR7 — Understandability
The design should be simple enough for both developers and non-architect stakeholders to follow.

## NFR8 — CLI ergonomics
The command-line surface should remain first-class rather than a temporary developer-only interface.

---

# Part D. Architectural implications

These use cases and requirements already imply several things:

1. The system must be **workspace-centered**.
2. The system must be **source-based**, not document-only.
3. LOQ-J must remain the **knowledge/evidence engine**.
4. Loqs must remain the **assistant/runtime shell**.
5. Research workflows and action workflows must remain separate.
6. Approval is a core design requirement, not a later patch.
7. Coding and learning are not side features; they are first-class use cases built on the same source/evidence foundation.
