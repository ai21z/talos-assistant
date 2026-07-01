# [done] Ticket: CLI UI Audit and Architecture Note
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- work-cycle-docs/tickets/new-work.md
- docs/architecture/talos-harness-source-of-truth.md
- docs/architecture/talos-harness-plan.md
- local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md
- work-cycle-docs/work-test-cycle.md
- work-cycle-docs/work-test-cycle-step-by-step.md
- .github/assistant-instructions.md

## Why This Ticket Exists

The beta CLI redesign should not start with a broad visual patch. Talos needs
an output architecture audit first so later UI changes improve trust,
debugability, safety, and script compatibility without destabilizing the
runtime.

## Problem

Talos already has a `Result` and `RenderEngine` boundary for most REPL output,
but launcher commands, approval prompts, RAG lazy indexing, setup, and some
core services still write directly to terminal streams. Color policy and debug
layering are also not first-class yet.

## Goal

Produce a tracked architecture note that maps the current output producers,
identifies pain points, defines the target renderer/theme/capability direction,
and proposes a safe ticket sequence.

## Scope

In scope:
- audit current CLI output architecture
- identify renderer-owned vs direct output
- identify debug/noise sources
- define safe follow-up tickets

Out of scope:
- runtime behavior changes
- startup redesign
- help redesign
- approval UI changes
- full renderer rewrite

## Proposed Work

Create `docs/architecture/30-cli-ui-output-architecture-audit.md`.

## Likely Files / Areas

- `docs/architecture/30-cli-ui-output-architecture-audit.md`

## Test / Verification Plan

No runtime behavior changes. Run focused CLI render/help/color tests as a
regression check.

## Acceptance Criteria

- audit note exists and is tracked
- current output producers are mapped
- target architecture is defined
- next implementation tickets are identified
- branch is committed and merged into `v0.9.0-beta-dev`
