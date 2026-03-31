# 16. Local Runtime and Model Selection

This document defines the intended architecture for local model usage, model choice, and hardware-aware guidance.

The goal is to make local execution and user trust explicit parts of the architecture.

---

## 1. Why this document matters

The project is local-first.

That means the architecture should explicitly describe:
- where local models fit into the system
- when the user chooses models
- how the system understands machine capabilities
- how the system suggests realistic local model profiles

If this is left vague, a major part of the local assistant story remains incomplete.

---

## 2. Architectural stance

Local model usage is part of the architecture, but it is **not the main center of the architecture**.

The main center remains:
- workspace
- source
- task
- evidence
- action
- approval

However, the system must still provide a clear model/runtime story because it is a local-first assistant.

---

## 3. Core concepts

## Hardware Profile
A **Hardware Profile** is the system's understanding of the user's machine capacity.

Examples of relevant inputs:
- CPU class
- RAM size
- GPU presence
- GPU VRAM size
- disk availability
- operating environment constraints

This concept should support recommendation, not become a noisy monitoring dashboard by default.

---

## Model Profile
A **Model Profile** is a selected group of local models appropriate for a usage pattern.

Examples:
- balanced profile
- coding-heavy profile
- low-resource profile
- vision-enabled profile

A model profile is a user-facing operating choice.

---

## Runtime Binding
A **Runtime Binding** is the relationship between a capability and a concrete local runtime/model choice.

Examples:
- general assistant runtime
- coding runtime
- retrieval embedding runtime
- reranker runtime
- vision runtime

This is more architectural than user-facing.

---

## 4. When the user chooses local models

The architecture should support model choice at several moments.

### A. Initial setup / onboarding
The system may inspect the machine and recommend one or more model profiles.

### B. Workspace or task configuration later
The user may prefer different model profiles for different kinds of work.

### C. On-demand override
The user may explicitly choose a stronger, lighter, or more specialized profile for a task.

### Important principle
The user should not be forced to understand every model detail in order to use the product.

The architecture should support:
- simple profile-level choice for most users
- deeper control for advanced users

---

## 5. Hardware awareness and suggestions

Yes, the architecture should support hardware-aware suggestions.

But it should do so carefully.

### What the system should do
- detect a hardware profile
- estimate realistic local capability levels
- recommend suitable model profiles
- warn when a model profile is unrealistic for the current machine

### What the system should not become too early
- a heavy always-on system monitor
- a distracting performance dashboard
- a model-management product before the assistant proves its value

So the architecture should support **hardware-aware recommendation**, not a monitoring obsession.

---

## 6. V1 stance on local model architecture

V1 should acknowledge and support:
- model profiles
- hardware-aware recommendation in principle
- clear runtime bindings in architecture

But V1 does **not** need to fully own:
- full model download lifecycle
- advanced runtime orchestration
- aggressive hardware telemetry surfaces

That deeper ownership can come later.

---

## 7. Relationship to the rest of the architecture

### Loqs runtime
Loqs should decide which capability is needed.

### Model/runtime layer
The runtime/model layer should determine which model profile or runtime binding should serve that capability.

### LOQ-J
LOQ-J may rely on specialized local runtimes for:
- embeddings
- retrieval support
- answer generation from evidence
- later reranking or multimodal support

### User-facing result
The user experiences one assistant, not a pile of runtime fragments.

---

## 8. Suggested architectural responsibilities

### Loqs runtime/platform owns
- user-visible model/profile choice flow
- when a task asks for a different profile level
- fallback and warning behavior at the assistant level

### Local runtime/model subsystem owns
- hardware profile detection
- model profile recommendation
- runtime binding decisions
- later model installation/runtime management if adopted

### LOQ-J owns
- knowledge-side use of relevant local runtimes
- not the whole product's model-management story

---

## 9. Data-protection implication

Model choice is also part of trust.

The user should be able to understand, at a high level:
- which tasks are staying local
- which model profile is being used locally
- whether a workflow depends on local-only execution

This does not require overwhelming the user with runtime trivia.
But the architecture should support local clarity.

---

## 10. Final stance

Yes, the architecture should explicitly include:
- when the user chooses local model profiles
- where hardware-aware suggestion happens
- how runtime bindings support different capabilities

This belongs to the architecture.

It is simply **not yet the center of the architecture**, and should be implemented in proportion to V1 scope.
