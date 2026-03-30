# 08. Capability Map

This document maps the project's major capabilities.

The goal is to make it clear:
- what the user-facing capability groups are
- which core concepts they depend on
- whether they are mainly Loqs responsibilities, LOQ-J responsibilities, or mixed

This helps keep the system understandable.

---

## 1. Why a capability map is useful

The project includes many intended abilities:
- search and summarization
- coding support
- learning support
- research
- action workflows
- workspace management
- memory
- local model usage

If we treat every one of these as a separate architectural foundation, the system becomes too fragmented.

The capability map helps show which user-facing abilities are actually built on the same shared foundations.

---

## 2. Core foundation capabilities

These are the capabilities that most of the rest of the system depends on.

## A. Workspace capability

### What it means
The system can operate within isolated workspace boundaries.

### Depends on
- workspace identity
- workspace scope
- workspace-aware state

### Mostly belongs to
- Loqs runtime/platform

---

## B. Source understanding capability

### What it means
The system can read and classify sources.

### Includes
- source registration
- source type recognition
- format recognition
- media type recognition
- parsing/extraction path selection

### Mostly belongs to
- shared foundation
- used heavily by LOQ-J

---

## C. Knowledge retrieval capability

### What it means
The system can retrieve evidence from workspace-scoped sources.

### Includes
- indexing
- chunking
- retrieval
- evidence preparation
- context pack assembly
- provenance/citations

### Mostly belongs to
- LOQ-J

---

## D. Task orchestration capability

### What it means
The system can turn user goals into runtime behavior.

### Includes
- task handling
- step sequencing
- capability selection
- approval triggering

### Mostly belongs to
- Loqs runtime/platform

---

## E. Safe action capability

### What it means
The system can perform concrete operations carefully.

### Includes
- file operations
- research-mode web operations
- later action-mode operations
- later message/draft/external-system operations

### Mostly belongs to
- Loqs runtime/platform

---

## F. Approval capability

### What it means
The system can stop and request explicit confirmation before risky work completes.

### Mostly belongs to
- Loqs runtime/platform

---

## G. Memory capability

### What it means
The system can preserve useful operational context separately from indexed sources.

### Mostly belongs to
- Loqs runtime/platform
- but used by multiple workflows

---

# 3. User-facing capability bundles

These are the main user-visible capability bundles built on top of the foundations.

## A. Document and source understanding

### User value
- summarize sources
- find facts
- compare sources
- explain important content

### Depends on
- workspace capability
- source understanding
- knowledge retrieval
- artifact generation

### Architecture note
This is not "document-only" anymore.
It should work for one or more sources of different kinds.

---

## B. Coding support

### User value
- explain repository structure
- explain how code works
- help understand technical systems
- later support safe coding workflows

### Depends on
- workspace capability
- source understanding
- knowledge retrieval
- task orchestration

### Architecture note
Coding is a capability bundle built on the same source/evidence foundation, not a separate architectural universe.

---

## C. Learning support

### User value
- explain a topic
- teach from selected materials
- produce study artifacts
- create learning plans

### Depends on
- workspace capability
- source understanding
- knowledge retrieval
- artifact generation

### Architecture note
Learning is also built on the same source/evidence foundation.

---

## D. Writing and drafting support

### User value
- draft replies
- rewrite content
- generate summaries and briefings

### Depends on
- workspace capability
- knowledge retrieval
- memory
- artifact generation

### Architecture note
Writing support is strongest when grounded in workspace evidence.

---

## E. Research capability

### User value
- search the web
- compare links
- summarize findings
- produce a research briefing

### Depends on
- task orchestration
- safe action capability
- research-mode behavior
- artifact generation

### Architecture note
Research mode is read-oriented and should stay distinct from action mode.

---

## F. Action workflow capability

### User value
- fill forms
- assist with bookings
- prepare external workflows
- later: support controlled operational steps

### Depends on
- task orchestration
- safe action capability
- approval capability
- workspace-aware policy context

### Architecture note
This is intentionally higher-risk than research.

---

## G. Daily briefing capability

### User value
- summarize what matters now
- combine relevant signals into one short output

### Depends on
- workspace capability
- knowledge retrieval
- artifact generation
- later memory and selected research capability

---

# 4. Capability ownership summary

## Mostly LOQ-J
- knowledge retrieval
- evidence preparation
- context pack assembly
- provenance/citations
- source-to-index transformation

## Mostly Loqs runtime/platform
- task orchestration
- workspace operating behavior
- approvals
- action execution
- research/action mode control
- user-facing CLI surface

## Shared foundation
- source understanding
- artifact concepts
- storage responsibility discipline
- runtime safety primitives

---

# 5. Capability priorities

To keep the project realistic, capabilities should be prioritized.

## Priority 1 — Core value now
- workspace capability
- source understanding
- knowledge retrieval
- summarization and explanation
- coding support
- learning support
- CLI-first task flow

## Priority 2 — Strong next wave
- drafting support
- daily briefing
- improved memory handling
- research mode

## Priority 3 — Later, higher risk
- action mode
- appointments
- shopping-related workflows
- broader connected-system execution

This priority order helps prevent the architecture from being dominated too early by high-risk action automation.

---

# 6. Final capability stance

The project should be understood as:

**one local assistant product composed of a small number of foundations, on top of which multiple user-facing capability bundles are built.**

That is much healthier than pretending every capability needs its own separate architecture from the start.
