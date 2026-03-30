# 02. Core Vocabulary

This document defines the shared language for the project.

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

A workspace is not only a directory. Its main role is **context isolation**.

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

The project should not be modeled only around "documents".

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

---

## 5. Media Type

**Media Type** describes the content modality relevant for processing.

Examples:
- TEXTUAL
- VISUAL
- STRUCTURED
- MIXED

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

---

## 7. Step

A **Step** is a unit of execution inside a Task.

This supports planning, tracing, retries, and approval points.

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

A task is the user goal. An action is a concrete operation used to achieve it.

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

Sources are mostly inputs. Artifacts are outputs.

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

Loqs should work from evidence rather than guessing.

---

## 11. Context Pack

A **Context Pack** is a curated bundle of evidence prepared for a task or step.

It should be relevant, bounded, ordered, provenance-aware, and ready for model consumption.

---

## 12. Memory

**Memory** is saved useful context that is not the same thing as a source.

Examples:
- user preferences
- prior decisions
- preferred writing style
- useful task outcomes
- workspace-specific operating context

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

---

## 15. Model Profile

A **Model Profile** is a selected local model setup for a machine or usage pattern.

Examples:
- balanced profile
- coding-heavy profile
- low-resource profile
- vision-enabled profile

---

## 16. Research Mode vs Action Mode

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
- upload files
- submit a booking
- prepare a purchase

These have different risk levels and safety requirements.

---

## 17. Simplest conceptual chain

**A user works inside a Workspace, asks Loqs to perform a Task, Loqs reads Sources, LOQ-J retrieves Evidence and assembles a Context Pack, Loqs performs Actions, produces Artifacts, stores useful Memory, and requests Approval for sensitive operations.**
