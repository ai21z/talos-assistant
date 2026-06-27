# [T886-open-medium] Configure, test, and write guides for all supported models

Status: open
Priority: medium
Blocker level: future milestone (deferred-beyond-beta). Owner marked this a
discussion item / arc, not for implementation now.

## Evidence Summary

- Source: owner manual REPL testing + discussion (2026-06-27)
- Date: 2026-06-27
- Talos version / commit: 0.10.6 / 4f8f50a7
- Verification status: scope captured; no code yet

Observed / request: from the `/models` discussion the owner concluded: "IF THE USER
NEEDS TO CONFIGURE A MODEL, we need to provide the corresponding guide ... we are
going to configure and test ALL those models and we will provide GUIDES." This is
the arc behind [T883] (which only clarified the `/models` tip wording). It pairs
with [T885] (terminal-UI configuration). The owner framed it as discussion, not
implementation, for now.

## Classification

Primary taxonomy bucket:

- `MODEL_COMPETENCE`

Secondary buckets:

- `UNSUPPORTED_CAPABILITY` (a model that is downloaded but not configured/guided)

Blocker level:

- future milestone

Why this level:

```text
Larger arc (multiple models x configure + test + guide). Owner asked to discuss and
plan, not build now. Must respect the model-stability invariant.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add docs for the models.
```

Architectural hypothesis:

```text
"Configure + test + guide" is one repeatable per-model recipe applied to a known
set, not ad hoc prose. Each model needs: a verified config (profile + backend), a
deterministic smoke test that proves it actually loads and answers under managed
llama.cpp, and a short guide a user can follow to get from "downloaded GGUF" to
"selectable". The doctrine-pinned stability models come first.
```

Likely code/document areas:

- `talos setup models` (profile config path)
- `src/main/java/dev/talos/engine/llamacpp/` (managed llama.cpp config + scan)
- model guides (location TBD: docs/ or work-cycle-docs/)

Why a one-off patch is insufficient:

```text
The value is the repeatable recipe across the full model set, not one model's notes.
```

## Goal

```text
For each supported model: a verified configuration, a deterministic test proving it
loads and answers, and a user-followable guide from downloaded -> selectable.
Start with the doctrine-pinned stability models, then the extras.
```

## Non-Goals

- No swapping or deprecating the doctrine-pinned stability models
  (`qwen2.5-coder-14b`, `gpt-oss-20b` on managed llama.cpp). Engineer for stability
  with these; do not propose a model swap as a fix.
- No overwriting `~/.talos/config.yaml` or `~/.talos/secrets` without explicit
  owner confirmation.
- No shell/browser/MCP/multi-agent behavior.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Implementation Notes

```text
Sequence (owner direction): start with qwen2.5-coder-14b and gpt-oss-20b (the
doctrine-pinned stability pair), get the configure->test->guide recipe solid on
those two, then extend to the other models the user may download (e.g. the live
default qwen3.6-35b-a3b and the abliterated eval model). One guide per model,
following the same shape.
```

## Architecture Metadata

Capability:

- model configuration + verification + documentation (no new runtime capability)

Operation(s):

- run (model smoke test), verify, write (guides; config via `talos setup models`)

Owning package/class:

- `talos setup models` config path + managed llama.cpp engine; guides as docs

New or changed tools:

- none expected; a smoke/verify harness at most

Risk, approval, and protected paths:

- Risk level: medium (touches managed-model config; stability invariant in play)
- Approval behavior: config writes gated as today; `~/.talos` not overwritten
  without explicit confirmation
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: checkpoint before any config write
- Evidence obligation: per-model smoke-test output captured as evidence
- Verification profile: a deterministic "model loads + answers" check
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: a guide claims a model works only with a captured passing
  smoke test behind it (no untested "supported")
- Trace/debug fields: model id, backend, smoke-test result

Refactor scope:

- `<allowed: a small per-model smoke/verify helper>`
- `<forbidden: changing the pinned stability models or a broad engine rewrite>`

## Acceptance Criteria

- A repeatable per-model recipe (configure -> test -> guide) exists and is applied
  first to `qwen2.5-coder-14b` and `gpt-oss-20b`.
- Each guide is backed by a deterministic smoke test that proves the model loads and
  answers; no model is documented as "supported" without passing evidence.
- The doctrine-pinned stability models are unchanged.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Integration/executor test: per-model "loads + answers" smoke check
- Evidence: captured smoke-test output referenced by each guide

Commands:

```powershell
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop when implemented; candidate loop only for versioned evidence.
- Behavior-changing closeout adds a one-line `## [Unreleased]` CHANGELOG entry.

## Known Risks

- Model instability is the named hazard; the stability invariant forbids "swap the
  model" as the fix.

## Known Follow-Ups

- Pairs with [T885] (terminal-UI configuration) and follows [T883] (the `/models`
  tip clarity that surfaced this arc).
