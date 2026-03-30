# 07. Runtime Shape

This document describes the intended runtime shape of the system at a high level.

The focus is on understanding the flow of the system, not on code classes or low-level implementation details.

---

## 1. Runtime stance

The project is **CLI-first**.

That means the runtime should be designed so that the command line is a first-class operating surface, not a temporary developer tool.

This runtime should support both:
- direct commands
- interactive session flow

---

## 2. One product outside, clear flow inside

The user-facing runtime is **Loqs**.

Internally, the runtime should coordinate several responsibilities:
- workspace selection
- task interpretation
- knowledge retrieval through LOQ-J
- optional action execution
- approval handling
- artifact production

This is the runtime shape we want, regardless of later module or package layout.

---

## 3. The core runtime flow

At the highest level, the runtime should behave like this:

1. The user enters or selects a **Workspace**
2. The user issues a **Task**
3. Loqs determines what kind of task it is
4. Loqs identifies what capabilities are needed
5. If local knowledge is needed, Loqs calls **LOQ-J**
6. LOQ-J returns **Evidence** and/or a **Context Pack**
7. Loqs answers directly or performs **Actions**
8. If the task is sensitive, Loqs asks for **Approval**
9. Loqs produces an **Artifact** or final response
10. Useful operational outcome may be recorded as **Memory** later

This is the core runtime chain.

---

## 4. Runtime layers

The runtime can be understood in four simple layers.

## A. CLI Surface Layer
This is what the user sees directly.

Examples:
- top-level commands
- interactive shell / REPL
- status commands
- task-oriented commands
- workspace-aware prompts

### Purpose
Accept user intent in a clear CLI-first form.

---

## B. Orchestration Layer
This is Loqs runtime behavior.

Responsibilities:
- interpret user request
- resolve workspace scope
- determine whether the task is knowledge-heavy, action-heavy, or mixed
- sequence steps
- invoke approval flow when needed

### Purpose
Turn user intent into system behavior.

---

## C. Knowledge Layer
This is LOQ-J.

Responsibilities:
- read relevant workspace knowledge structures
- retrieve evidence
- pack context
- return provenance-aware support for the task

### Purpose
Provide grounded context for the runtime.

---

## D. Capability Execution Layer
This is where concrete actions happen.

Examples:
- file operations
- research-mode web reading
- later action-mode web operations
- format conversion
- draft generation integration

### Purpose
Perform concrete operations safely.

---

## 5. Runtime modes should remain simple

The system may expose different user-facing modes, but mode design should remain simple and intentional.

The runtime should not become a confusing collection of loosely related personalities.

A healthy direction is:
- workspace-aware operation first
- task-oriented routing second
- mode names only when they clearly help the user

In other words:

**the runtime should be capability-driven, not gimmick-driven.**

---

## 6. Research mode and action mode

The runtime must keep these distinct.

## Research mode
Purpose:
- search
- read
- extract
- summarize
- compare

Expected behavior:
- lower risk
- evidence-oriented
- read-first

## Action mode
Purpose:
- fill forms
- upload files
- submit requests
- prepare external workflows

Expected behavior:
- higher risk
- approval-sensitive
- policy-sensitive

This distinction should exist at runtime, not only in documentation.

---

## 7. Workspace awareness in runtime

The runtime should always be conscious of workspace context.

That means:
- commands should know which workspace they operate on
- retrieval should resolve against workspace scope by default
- actions should understand workspace policy context
- status and diagnostics should be workspace-aware

If the user crosses workspace boundaries later, that should be explicit.

---

## 8. Runtime and memory

Memory should not dominate the runtime too early.

The runtime should support memory carefully and separately from source retrieval.

### Good runtime relationship to memory
- read memory when it clearly helps
- write memory only for useful operational outcomes
- preserve workspace-aware memory boundaries

### Bad runtime relationship to memory
- treating memory as a magical replacement for sources
- mixing every conversation fragment into permanent truth

---

## 9. Runtime and approval

Approval should be treated as a normal part of runtime behavior.

Approval is not an exception case.
It is one of the standard runtime decisions.

Examples:
- show user pending action
- ask for approval
- continue or cancel
- produce result or safe refusal

The runtime shape should make this natural.

---

## 10. Runtime and CLI command surface

The final CLI should reflect the architecture clearly.

A good future direction is a task/capability-oriented command surface under one product name.

Examples of the intended spirit:
- `loqs workspace ...`
- `loqs source ...`
- `loqs knowledge ...`
- `loqs code ...`
- `loqs learn ...`
- `loqs task ...`
- `loqs browse ...`

This is not a final command design.

It is only a runtime-shape signal:

**one CLI product, multiple coherent capability surfaces.**

---

## 11. Runtime and LOQ-J relationship

The runtime should call LOQ-J as a subsystem, not dissolve it into generic command logic.

That means the runtime should not own:
- retrieval internals
- chunking internals
- context packing internals
- provenance internals

The runtime should consume those services from LOQ-J.

This is one of the most important runtime boundary decisions.

---

## 12. Runtime shape summary

The intended runtime shape is:

- **CLI-first**
- **workspace-aware**
- **task-driven**
- **knowledge-backed through LOQ-J**
- **capability-based for concrete operations**
- **approval-aware for sensitive actions**

In one sentence:

**Loqs should feel like one local CLI-first assistant, while internally coordinating workspace scope, task flow, LOQ-J knowledge retrieval, and safe capability execution.**
