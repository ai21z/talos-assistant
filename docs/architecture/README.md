# Loqs / LOQ-J Architecture

This folder contains the first architecture foundation for the project.

The goal is to keep the design simple, local-first, and easy to understand for both product and development work.

## Current stance

- **Loqs** is the single user-facing local assistant product.
- **LOQ-J** is the internal knowledge and context engine inside Loqs.
- The project remains **CLI-first**.
- We are intentionally defining **use cases, requirements, vocabulary, and boundaries before code changes**.

## Document map

1. [01-product-and-scope.md](./01-product-and-scope.md)
   - product identity
   - project goals
   - scope and non-goals

2. [02-core-vocabulary.md](./02-core-vocabulary.md)
   - shared language for product, architecture, and development
   - stable core abstractions

3. [03-core-use-cases-and-requirements.md](./03-core-use-cases-and-requirements.md)
   - main user goals
   - initial functional and non-functional requirements

4. [04-system-boundaries.md](./04-system-boundaries.md)
   - what belongs to Loqs
   - what belongs to LOQ-J
   - what is shared platform/runtime behavior

## Design principles

- local-first by default
- workspace-scoped context
- private data stays private
- retrieval and evidence before guessing
- approval before sensitive actions
- one product outside, clear subsystems inside
- CLI-first, modular, understandable

## Notes

This is intentionally **architecture-first documentation**.

It is not a code design document yet.
It is not a persistence schema yet.
It is not a class diagram yet.

Those will come later, after the concepts and system boundaries are stable.
