# T708 - Hierarchical Project Memory

Status: in-progress
Priority: high
Created: 2026-06-06

## Evidence Summary

- Source: static architecture/code review plus research synthesis
- Date: 2026-06-06
- Talos version / commit: `0.9.9` / `dd67d6864e3ccb084f1efef532930e0824ef3c15`
- Evidence:
  - `work-cycle-docs/research/context-retrieval-memory-best-techniques-from-reference-systems.md`
  - `src/main/java/dev/talos/runtime/SessionMemory.java`
  - `src/main/java/dev/talos/runtime/context/ActiveTaskContext.java`
  - `src/main/java/dev/talos/runtime/JsonSessionStore.java`

Expected behavior:

```text
Talos should support visible, deterministic project memory loaded by tier and
budget, without hidden vector-memory behavior and without overriding current
user instructions or AGENTS.md.
```

Observed behavior:

```text
Talos has session memory and active task context, but no explicit hierarchical
project-memory layer comparable to TALOS.md / .talos/rules.md / directory-local
memory with deterministic precedence and prompt-debug visibility.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `TRACE_REDACTION`
- `OUTCOME_TRUTH`

Blocker level:

- future milestone

Why this level:

```text
This is not needed to close the current static-web bug, but it is the highest
confidence memory architecture direction after T707. It should be implemented
before investing in vector memory.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add memory.
```

Architectural hypothesis:

```text
Talos needs a visible, trust-scoped, hierarchical Markdown memory layer that
feeds current-turn context deterministically. This complements ActiveTaskContext;
it does not replace task contracts, approval, verification, or trace evidence.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/SessionMemory.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/main/java/dev/talos/cli/prompt/`
- `src/main/java/dev/talos/runtime/trace/`
- `docs/architecture/`

Why a one-off patch is insufficient:

```text
Unstructured hidden memory would create the same audit problem as stale session
state: Talos could answer from invisible context. The invariant must be loaded
by tier, redacted, bounded, and visible in prompt-debug/trace.
```

## Goal

```text
Add a project-memory design and implementation spine for visible hierarchical
Markdown memory, with deterministic precedence and explicit trace/prompt-debug
rendering.
```

## Non-Goals

- No vector memory.
- No autonomous memory writes.
- No hidden user-profile inference.
- No replacement of `ActiveTaskContext`.
- No overriding `AGENTS.md` or current user instructions.

## Implementation Notes

Initial direction:

- Define accepted memory filenames and tiers, for example global user memory,
  workspace memory, repo memory, and directory-local memory.
- Load memory read-only with bounded byte/line budgets.
- Apply deterministic precedence: current user request and AGENTS/project policy
  win over memory.
- Surface loaded memory tier/source in prompt-debug and `/last trace`.
- Add explicit redaction and protected-path behavior before including memory in
  model context.

Implementation scope update, 2026-06-07:

- Implement as three gated slices: discovery/policy, prompt rendering, then
  trace/prompt-debug hardening.
- Memory is read-only and reloaded each eligible turn. It is not persisted into
  session summaries and is not a user-profile inference layer.
- Supported files in this ticket are limited to Talos-owned Markdown memory
  files: `TALOS.md`, `.talos/rules.md`, and bounded top-level
  `%USERPROFILE%/.talos/memory/*.md`.
- No include/import expansion, no foreign `external assistant.md`/`GEMINI.md` support, no
  semantic rule interpreter, and no vector memory in this ticket.
- Memory content must be rendered as untrusted context. It must not be treated
  as approval, runtime policy, verifier evidence, or proof that the workspace
  was inspected.

## Architecture Metadata

Capability:

- Project memory / context assembly

Operation(s):

- read

Owning package/class:

- New runtime/context or cli/prompt owner; exact owner to be designed before code.

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: no mutation approval; protected reads remain denied/approved
  according to existing policy
- Protected path behavior: memory loader must not bypass protected-path policy

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: loaded memory sources must be traceable
- Verification profile: none
- Repair profile: none

Outcome and trace:

- Outcome/truth warnings: final answers must not present memory as inspected
  workspace evidence
- Trace/debug fields: loaded memory files, tier, truncation, redaction

Refactor scope:

- Allowed: add a dedicated memory loader/context-frame component
- Forbidden: broad rewrite of session storage or prompt assembly

## Acceptance Criteria

- Hierarchical memory design doc identifies tiers, precedence, budgets, and trust
  boundaries.
- Runtime loads allowed memory files deterministically and renders source/tier in
  prompt-debug.
- Current user instructions override memory.
- Status/small-talk/privacy turns do not leak project memory unnecessarily.
- Tests cover precedence, truncation, redaction, protected paths, and prompt-debug
  visibility.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: memory tier ordering and truncation.
- Integration/executor test: current-turn frame includes visible memory metadata.
- Trace assertion: loaded memory source/tier/redaction recorded.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.context.*" --tests "dev.talos.cli.prompt.*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Start with design and tests.
- Do not implement vector memory.
- Do not add persistent writes until read-only memory is audited.

## Known Risks

- Hidden memory can become a truthfulness/privacy problem if not surfaced.
- Directory-local memory can create confusing precedence unless prompt-debug is explicit.

## Known Follow-Ups

- Optional user-approved memory writes after read-only hierarchy is stable.
