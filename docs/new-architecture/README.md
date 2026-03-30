# Loqs / LOQ-J New Architecture

This folder contains the current self-contained architecture pack for the project.

It is intended to be the main architecture reading path for:
- the project owner
- Claude Opus as developer
- future contributors

## Current stance

- **Loqs** is the single user-facing local assistant product.
- **LOQ-J** is the internal knowledge and context engine inside Loqs.
- The project remains **CLI-first**.
- We are intentionally defining **use cases, requirements, vocabulary, boundaries, and storage responsibilities before code changes**.

## Reading order

0. [00-executive-summary.md](./00-executive-summary.md)
   - short architect brief
   - the whole project in one document

1. [01-product-and-scope.md](./01-product-and-scope.md)
   - product identity
   - goals
   - scope and non-goals

2. [02-core-vocabulary.md](./02-core-vocabulary.md)
   - shared language
   - core abstractions

3. [03-core-use-cases-and-requirements.md](./03-core-use-cases-and-requirements.md)
   - main user goals
   - functional and non-functional requirements

4. [04-system-boundaries.md](./04-system-boundaries.md)
   - Loqs vs LOQ-J vs shared platform responsibilities

5. [05-storage-responsibilities.md](./05-storage-responsibilities.md)
   - truth ownership by storage role

6. [06-workspace-model.md](./06-workspace-model.md)
   - workspace behavior and context boundaries

7. [07-runtime-shape.md](./07-runtime-shape.md)
   - CLI-first runtime flow

8. [08-capability-map.md](./08-capability-map.md)
   - foundation capabilities and user-facing bundles

9. [09-architecture-decisions.md](./09-architecture-decisions.md)
   - key architecture decisions

10. [10-roadmap-from-current-loqj.md](./10-roadmap-from-current-loqj.md)
    - conceptual migration path from current LOQ-J to Loqs

11. [11-open-questions.md](./11-open-questions.md)
    - visible unresolved questions

12. [12-v1-scope.md](./12-v1-scope.md)
    - focused V1 scope

13. [13-what-not-to-build-yet.md](./13-what-not-to-build-yet.md)
    - anti-scope-drift guardrails

14. [14-next-steps-for-developer.md](./14-next-steps-for-developer.md)
    - practical handoff for development work

## Design principles

- local-first by default
- workspace-scoped context
- private data stays private
- retrieval and evidence before guessing
- approval before sensitive actions
- one product outside, clear subsystems inside
- CLI-first, modular, understandable

## Notes

This pack is intentionally **architecture-first**.

It is not a code design pack yet.
It is not a persistence schema yet.
It is not a class diagram yet.

Those come later, after the concepts and boundaries are stable.
