# 01. Product and Scope

## Product identity

### User-facing product
**Loqs** is the user-facing product.

Loqs is a **local-first, CLI-first assistant** for:
- knowledge and documents
- digital work and personal admin
- coding and repository understanding
- learning and research
- carefully controlled actions

### Internal subsystem
**LOQ-J** is the knowledge and context engine inside Loqs.

LOQ-J is responsible for turning local sources into usable evidence and context.

## Why this split exists

This is **not** a split into two unrelated products.

It is a split between:
- the **assistant platform** the user interacts with
- the **knowledge engine** that powers retrieval, evidence, and context assembly

In simple terms:
- **Loqs** decides and helps
- **LOQ-J** knows and retrieves

## Project goal

Create a local assistant that can help users with real daily digital work while keeping private data under local control.

The long-term goal is not to be a generic chatbot.

The goal is to become a **trusted local operator** that can:
- understand user intent
- use local knowledge safely
- search and explain sources
- help write and summarize
- support coding and learning
- perform actions carefully with approval when needed

## Product principles

### 1. Local-first
The system should prefer local data, local models, and local execution wherever practical.

### 2. Workspace-centered
The system should organize work through isolated workspaces so context does not leak across domains.

### 3. Evidence-driven
The assistant should retrieve and cite evidence instead of guessing when a task depends on local knowledge.

### 4. Safe action model
Read-oriented tasks and action-oriented tasks must be separated. Sensitive actions must require approval.

### 5. CLI-first experience
The project should remain comfortable and powerful from the command line.

### 6. Clear boundaries
The knowledge engine, runtime orchestration, actions, memory, and later model management must remain understandable as separate concerns.

## What the product is not

At this stage, the project is **not**:
- a cloud-first SaaS
- a web app that requires a remote database to function
- a browser-only agent
- a pure coding assistant only
- a pure document search tool only
- a multi-agent research playground with no product discipline

## Target user value

The user should be able to say things like:
- "search my local sources and explain what matters"
- "summarize this file or compare these sources"
- "explain this codebase"
- "teach me this topic from selected materials"
- "draft a reply using workspace context"
- "research this on the web"
- "do this action, but ask me before anything sensitive"

## Core product capabilities

Loqs should eventually cover these capability groups:

### A. Source understanding
- read sources from a workspace
- classify and parse them
- support different source types and formats
- prepare them for retrieval and explanation

### B. Knowledge retrieval
- index local sources
- retrieve relevant evidence
- assemble context packs
- preserve provenance/citations

### C. Assistant workflows
- execute tasks
- break work into steps
- use evidence and tools
- produce artifacts

### D. Controlled actions
- file operations
- web research
- later: appointments, shopping, email, calendar
- always with approval for sensitive operations

### E. Memory
- preserve useful preferences and task outcomes
- support workspace memory and global preferences separately

### F. Learning and coding support
- explain repositories
- help understand systems and concepts
- teach from selected materials

## Current non-goals

To keep the architecture disciplined, the following are **not primary goals right now**:
- full autonomous browser operation without approval
- advanced multi-agent topology as the main architecture driver
- remote/cloud storage as the default model
- large UI framework decisions before the CLI architecture is stable
- premature database/schema design before concepts are stable

## Architectural consequence

Because of the above, the project should be designed as:
- **one assistant product**
- **with clear internal subsystems**
- **with LOQ-J preserved as the knowledge/context engine**

That is the guiding product decision for all later architecture work.
