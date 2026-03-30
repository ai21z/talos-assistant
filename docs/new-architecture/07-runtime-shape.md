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

---

## 3. The core runtime flow

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

---

## 4. Runtime layers

### A. CLI Surface Layer
What the user sees directly.

### B. Orchestration Layer
Interprets user request and sequences behavior.

### C. Knowledge Layer
This is LOQ-J: retrieval, evidence, context.

### D. Capability Execution Layer
Concrete operations such as file work, research-mode browsing, and later action-mode work.

---

## 5. Runtime modes should remain simple

The runtime should be capability-driven, not gimmick-driven.

It should favor:
- workspace-aware operation first
- task-oriented routing second
- mode names only when they clearly help the user

---

## 6. Research mode and action mode

### Research mode
Purpose:
- search
- read
- extract
- summarize
- compare

### Action mode
Purpose:
- fill forms
- upload files
- submit requests
- prepare external workflows

The runtime must keep these distinct.

---

## 7. Workspace awareness in runtime

The runtime should always be conscious of workspace context.

That means:
- commands should know which workspace they operate on
- retrieval should resolve against workspace scope by default
- actions should understand workspace policy context
- status and diagnostics should be workspace-aware

---

## 8. Runtime and memory

Memory should not dominate the runtime too early.

Good runtime relationship to memory:
- read memory when it clearly helps
- write memory only for useful operational outcomes
- preserve workspace-aware memory boundaries

Bad runtime relationship to memory:
- treating memory as a magical replacement for sources
- mixing every conversation fragment into permanent truth

---

## 9. Runtime and approval

Approval should be treated as a normal part of runtime behavior.

Examples:
- show user pending action
- ask for approval
- continue or cancel
- produce result or safe refusal

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

---

## 11. Runtime and LOQ-J relationship

The runtime should call LOQ-J as a subsystem, not dissolve it into generic command logic.

The runtime should not own:
- retrieval internals
- chunking internals
- context packing internals
- provenance internals

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
