# 02. Core Vocabulary

This document defines the shared language for the project.

The goal is to avoid confusion between product language, architecture language, and implementation language.

These concepts should remain simple, stable, and understandable.

---

## 1. Workspace

A **Workspace** is a private local context boundary.

A workspace groups together:
- sources
- knowledge/index scope
- memory scope
- task history
- permissions and policies
- later: allowed tools/sites/model preferences

### Why it matters
Without workspaces, context leaks across unrelated domains such as:
- work
- personal admin
- learning
- coding
- shopping
- appointments

### What a workspace is not
A workspace is not only a directory.
A workspace may reference one or more directories or sources, but its main role is **context isolation**.

---

## 2. Source

A **Source** is anything Loqs can read, inspect, index, summarize, compare, or use as context.

Examples:
- PDF
- DOCX
- TXT
- Markdown file
- code file
- repository
- email thread
- webpage
- screenshot
- spreadsheet
- slide deck

### Why this abstraction is important
The project should not be modeled only around "documents".

Coding, learning, document work, email understanding, and web research all depend on reading and understanding **sources**.

---

## 3. Source Type

**Source Type** is the semantic category of a source.

Examples:
- DOCUMENT
- CODE_FILE
- REPOSITORY
- EMAIL_THREAD
- WEBPAGE
- IMAGE
- SPREADSHEET
- SLIDE_DECK
- NOTE_SET

### Why it matters
Different source types require different behavior.

Examples:
- a repository may be traversed recursively
- a PDF may need page-based parsing
- an email thread may need threading logic
- an image may require vision support

---

## 4. Format

**Format** is the concrete technical format of a source.

Examples:
- PDF
- DOCX
- TXT
- MD
- HTML
- EML
- CSV
- XLSX
- PPTX
- PNG
- JPG
- JAVA
- TS
- PY

### Why it matters
Two sources may have the same source type but different formats.

Example:
- a DOCUMENT may be PDF or DOCX
- a CODE_FILE may be JAVA or TS

---

## 5. Media Type

**Media Type** describes the content modality relevant for processing.

Examples:
- TEXTUAL
- VISUAL
- STRUCTURED
- MIXED

### Why it matters
Media type helps decide the processing pipeline.

Examples:
- textual parsing
- OCR / vision extraction
- table extraction
- mixed multimodal handling

---

## 6. Task

A **Task** is a user goal that Loqs is trying to accomplish.

Examples:
- summarize a source
- compare sources
- explain a codebase
- draft an email reply
- research a topic
- prepare a daily briefing

A task is the top-level unit of work.

---

## 7. Step

A **Step** is a unit of execution inside a Task.

### Why it matters
This supports:
- planning
- tracing
- retries
- approval points
- human-in-the-loop operation

A task may contain one or more steps.

---

## 8. Action

An **Action** is a concrete operation executed by the system.

Examples:
- read a file
- search an index
- fetch a webpage
- click a button
- fill a form field
- create a draft
- convert a file

### Important distinction
A task is the user goal.
An action is a concrete operation used to achieve it.

---

## 9. Artifact

An **Artifact** is something produced by Loqs.

Examples:
- summary
- comparison report
- email draft
- translation
- lesson
- extracted deadline list
- converted file
- daily briefing

### Important distinction
Sources are mostly inputs.
Artifacts are outputs.

---

## 10. Evidence

**Evidence** is the supporting context retrieved from sources and used to answer or act.

Examples:
- document chunks
- code snippets
- extracted clauses
- email excerpts
- webpage text blocks
- structured rows/cells

### Why it matters
Loqs should work from evidence rather than guessing.

Evidence is one of the most important concepts in the system.

---

## 11. Context Pack

A **Context Pack** is a curated bundle of evidence prepared for a task or step.

It is higher-level than raw retrieval results.

A context pack should be:
- relevant
- bounded
- ordered
- provenance-aware
- ready for model consumption

This is one of LOQ-J's main responsibilities.

---

## 12. Memory

**Memory** is saved useful context that is not the same thing as a source.

Examples:
- user preferences
- prior decisions
- preferred writing style
- useful task outcomes
- workspace-specific operating context

### Important distinction
Memory is not just another document.
It is retained operational knowledge.

---

## 13. Approval

An **Approval** is explicit user permission required before a sensitive action continues.

Examples:
- sending an email
- submitting a form
- uploading a file
- booking an appointment
- confirming a purchase
- deleting content

### Why it matters
Approval is central to trust and safety.
It is not an afterthought.

---

## 14. Capability

A **Capability** is a named system ability that can be used to perform work.

Examples:
- knowledge retrieval
- file reading
- browser research
- browser action
- email drafting
- format conversion
- repository explanation

This term is useful at the architectural level before going into code/tool details.

---

## 15. Model Profile

A **Model Profile** is a selected local model setup for a machine or usage pattern.

Examples:
- balanced profile
- coding-heavy profile
- low-resource profile
- vision-enabled profile

This belongs to the system but is not the main architectural center right now.

---

## 16. Research Mode vs Action Mode

These two terms should stay separate.

### Research Mode
Read-oriented interaction.
Examples:
- search the web
- open links
- extract and summarize content
- compare sources

### Action Mode
Execution-oriented interaction.
Examples:
- fill forms
- click through a workflow
- upload a file
- submit a booking
- prepare a purchase

### Why the distinction matters
These modes have different:
- risk levels
- permission needs
- user expectations
- safety requirements

---

## 17. The simplest conceptual chain

The core model of the system can be expressed like this:

**A user works inside a Workspace, asks Loqs to perform a Task, Loqs reads Sources, LOQ-J retrieves Evidence and assembles a Context Pack, Loqs performs Actions, produces Artifacts, stores useful Memory, and requests Approval for sensitive operations.**

This sentence is the backbone of the project vocabulary.
