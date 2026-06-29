# [T886-done-medium] Configure, test, and write guides for all supported models

Status: done
Priority: medium
Blocker level: closed. Owner promoted this from deferred discussion item to
implementation on 2026-06-30.

## Evidence Summary

- Source: owner manual REPL testing + discussion (2026-06-27)
- Date: 2026-06-27
- Original discussion version / commit: 0.10.6 / 4f8f50a7
- Implementation baseline version / commit: 0.10.6 / 6d077439
- Verification status: implemented with focused red/green tests; full `check`
  passed because runtime code changed

Observed / request: from the `/models` discussion the owner concluded: "IF THE USER
NEEDS TO CONFIGURE A MODEL, we need to provide the corresponding guide ... we are
going to configure and test ALL those models and we will provide GUIDES." This is
the arc behind [T883] (which only clarified the `/models` tip wording). It pairs
with [T885] (terminal-UI configuration). The owner later promoted T886 ahead of
T885 because it is adjacent to the model setup/support surface.

## Classification

Primary taxonomy bucket:

- `MODEL_COMPETENCE`

Secondary buckets:

- `UNSUPPORTED_CAPABILITY` (a model that is downloaded but not configured/guided)

Blocker level:

- closed

Why this level:

```text
Implemented as the bounded model-support surface: shared profile metadata,
deterministic doctor smoke semantics, and per-profile guides. The stability
invariant is preserved by separating accepted beta profiles from experimental
selectable profiles.
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
- `docs/user/model-profiles/` (one user guide per canned chat profile)

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

Implemented 2026-06-30:

- `LlamaCppModelProfiles.CannedProfile` now carries support tier, tool mode,
  guide path, and evidence-boundary text as the canonical profile metadata.
- `talos setup models` help renders profile sections from that registry instead
  of a hardcoded table.
- Accepted beta stability profiles are explicitly limited to
  `qwen2.5-coder-14b` and `gpt-oss-20b`.
- Qwen3.6-VibeForged and DeepSeek-Coder-V2-Lite are explicitly labeled
  experimental selectable profiles, not beta stability baselines.
- `talos doctor --start` now requires the deterministic
  `TALOS_MODEL_SMOKE_OK` reply token before reporting model-smoke success.
- Per-profile guides live under `docs/user/model-profiles/`.

## Architecture Metadata

Capability:

- model configuration + verification + documentation (no new runtime capability)

Operation(s):

- run (model smoke test), verify, write (guides; config via `talos setup models`)

Owning package/class:

- `talos setup models` config path + managed llama.cpp engine; guides as docs

New or changed tools:

- no new command; `talos doctor --start` smoke semantics tightened

Risk, approval, and protected paths:

- Risk level: medium (touches managed-model config; stability invariant in play)
- Approval behavior: config writes gated as today; `~/.talos` not overwritten
  without explicit confirmation
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged; this ticket does not write user config
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

- [x] A repeatable per-model recipe (configure -> test -> guide) exists and is applied
  first to `qwen2.5-coder-14b` and `gpt-oss-20b`.
- [x] Each guide is backed by a deterministic smoke test that proves the model loads and
  answers; no model is documented as "supported" without passing evidence.
- [x] The doctrine-pinned stability models are unchanged.
- [x] No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Implemented deterministic regressions:

- `DoctorProbesTest` pins that `talos doctor --start` fails if the chat reply
  does not contain `TALOS_MODEL_SMOKE_OK`.
- `LlamaCppModelProfilesTest` pins support-tier separation and profile guide
  metadata for all canned profiles.
- `SetupCmdTest` pins registry-rendered setup help, support-tier headings,
  guide links, and `talos doctor --start` guidance.
- `TrustClaimsHonestyTest` pins the accepted/experimental wording and one guide
  per canned chat profile.

Evidence:

- Hugging Face model API checks confirmed the configured GGUF filenames for
  Qwen2.5-Coder, GPT-OSS, Qwen3.6-VibeForged Q4/Q6, DeepSeek-Coder-V2-Lite, and
  `bge-m3` on 2026-06-30.
- Focused red/green gate:
  `.\gradlew.bat test --tests "dev.talos.engine.llamacpp.LlamaCppModelProfilesTest" --tests "dev.talos.cli.launcher.SetupCmdTest" --tests "dev.talos.docs.TrustClaimsHonestyTest" --tests "dev.talos.cli.doctor.DoctorProbesTest" --no-daemon`
  passed after implementation.
- Full runtime gate: `.\gradlew.bat check --no-daemon` passed after
  implementation.

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
