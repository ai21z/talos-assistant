# 20. Reference Study: Cutting-Edge Direction Without Losing Discipline

This document records the architectural lessons from selected reference points that matter to the project direction.

The goal is not to copy other systems blindly.
The goal is to learn from strong patterns while preserving Loqs' disciplined V1 path.

---

## 1. References considered

The current reference set includes:
- OpenClaw
- NVIDIA NemoClaw
- the LLM Agents From Scratch book/repo direction
- the Hermes-like direction discussed for learning/adaptation
- the current Loqs / LOQ-J architecture plan

---

## 2. OpenClaw: what matters architecturally

OpenClaw is important because it proves that a locally run assistant can feel like a real product rather than a toy.

The most important architectural lessons are:
- the assistant itself is the product
- local operation is part of the value story
- onboarding matters
- channels/integrations can make the assistant feel always available
- the control plane and the assistant experience should be conceptually distinct

### What Loqs should take
- one clear product identity
- strong onboarding eventually
- local-first as product value, not only implementation detail
- the idea that the assistant experience should feel coherent rather than like a bag of tools

### What Loqs should not copy too early
- broad connected-system execution as an early center
- extensive action surface before trust/hardening is mature

---

## 3. NVIDIA NemoClaw: what matters architecturally

NemoClaw is important because it shows a serious answer to the question:

**How do you run an always-on agent more safely?**

The most important lessons are:
- sandboxing and runtime hardening matter
- layered protection should be explicit
- guided onboarding can coexist with strong controls
- network policy and approval are not afterthoughts
- routed inference and profile-style runtime choice matter in local/secure operation

### What Loqs should take
- treat runtime trust as architecture, not as a later patch
- keep research mode and action mode clearly distinct
- build toward stronger sandbox/policy execution later
- support guided onboarding and profile-based setup
- support runtime/profile routing without making it the center too early

### What Loqs should not copy too early
- a full hardened execution stack as V1 center
- operational complexity that overshadows core source/evidence value

---

## 4. LLM Agents From Scratch: what matters architecturally

The book/repo direction matters because it reinforces foundational agent discipline.

The important lessons are:
- tools need explicit contracts
- agent work should be step-oriented
- execution history/rollout matters
- MCP compatibility matters as a protocol direction
- memory and human-in-the-loop should be treated as deliberate enhancements, not magic

### What Loqs should take
- keep task execution understandable in step form
- keep approval/human review as a first-class idea
- support protocol-friendly tool/capability design later
- preserve traceability where it helps trust and debugging

### What Loqs should not copy blindly
- educational from-scratch implementation as the product architecture
- framework-building for its own sake instead of product value

---

## 5. Hermes-like learning direction: what matters architecturally

The Hermes-like direction matters because it points toward a more adaptive and improving assistant.

The strongest reusable pattern is:
- learn useful behavior over time
- improve defaults
- remember preferences and repeated workflows
- become more helpful without becoming uncontrolled

### What Loqs should take
- adaptive behavior should be workspace-aware first
- learning should improve usefulness and accessibility
- reusable task patterns and profile recommendations are valuable

### What Loqs should not do
- create a giant undifferentiated memory blob
- allow vague "self-learning" language to replace explicit architecture
- let learning distort V1 scope

---

## 6. Comparison with the current Loqs / LOQ-J strategy

The current project direction is already strong in several ways:
- it has one product identity
- it preserves LOQ-J as a knowledge engine
- it is workspace-centered
- it is source/evidence-driven
- it treats approval as first-class
- it is increasingly explicit about local trust, hardware awareness, model profiles, and accessibility

This means the architecture is already compatible with:
- stronger runtime hardening later
- guided onboarding later
- adaptive assistance later
- multiple surfaces later

The important thing is that the current architecture remains more disciplined than many cutting-edge agent projects.

That is a strength, not a weakness.

---

## 7. What should be stolen now vs later

## Steal now
- product coherence
- clear subsystem boundaries
- workspace discipline
- approval/human review discipline
- profile-based model/runtime thinking
- local trust as an architectural concern
- step-oriented task reasoning and traceability

## Steal later
- hardened sandbox/runtime execution patterns
- richer runtime routing
- adaptive workflow learning
- more guided onboarding for non-technical users

## Do not steal as a default posture
- scope explosion
- "always-on automation" as the main early identity
- giant magical memory systems
- multi-agent complexity before the core is proven

---

## 8. Final stance

The right strategy is:

**Keep V1 disciplined around workspaces, sources, evidence, LOQ-J retrieval value, local trust, and coherent CLI operation — while deliberately tracking cutting-edge patterns in security, onboarding, runtime routing, and adaptive assistance for later phases.**

That keeps the project modern without letting it drift.
