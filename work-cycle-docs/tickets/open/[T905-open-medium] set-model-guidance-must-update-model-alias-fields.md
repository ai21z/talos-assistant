# [T905-open-medium] Set-model guidance must update model alias fields

Status: open
Priority: medium

## Evidence Summary

- Source: static review of T902 remediation plus installed CLI smoke
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 7ac05041
- Model/backend: llama_cpp / qwen2.5-coder-14b
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli`
- Raw transcript path: n/a; installed smoke run in current terminal
- Trace path or `/last trace` summary: n/a; slash-command-only smoke
- File diff summary: none
- Approval choices: none
- Checkpoint id: n/a
- Verification status: focused tests pass, but review found a coverage gap

Redacted prompt sequence:

```text
/set model gpt-oss-20b-mxfp4
```

Expected behavior:

```text
When `/set model` sees a downloaded-but-unconfigured canned GGUF, the guidance
must tell the user every config field needed to make the switch truthful and
auditable after restart.
```

Observed behavior:

```text
The installed command prints concrete `hf_repo` and `hf_file`, but omits the
model alias fields:

  llm.model
  engines.llama_cpp.model

It then says "restart Talos and confirm with /models".
```

Code evidence:

- `SetModelCommand.modelNotFoundMessage(...)` prints only `hf_repo` and
  `hf_file` for the config-edit path:
  `src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java`.
- `EngineRuntimeConfig.from(...)` gives `llm.model` priority over backend model
  fallback when reporting runtime model identity:
  `src/main/java/dev/talos/core/EngineRuntimeConfig.java`.
- `EngineRuntimeConfig.backendModel(...)` gives `engines.llama_cpp.model`
  priority over `hf_repo` for the llama.cpp runtime label/catalog identity:
  `src/main/java/dev/talos/core/EngineRuntimeConfig.java`.
- `LlamaCppConfig.catalogFallbackModel()` gives `engines.llama_cpp.model`
  priority over `hf_repo`, and `LlamaCppServerManager.buildCommand()` passes it
  to llama.cpp as `--alias`:
  `src/main/java/dev/talos/engine/llamacpp/LlamaCppConfig.java` and
  `src/main/java/dev/talos/engine/llamacpp/LlamaCppServerManager.java`.

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `TOOL_SURFACE`
- `CURRENT_TURN_FRAME`

Blocker level:

- candidate follow-up

Why this level:

```text
The no-hot-swap refusal is correct, and the concrete Hugging Face fields are
better than literal placeholders. But the instruction is still not a complete
switch recipe: following only the shown fields can load a different GGUF while
leaving Talos and llama.cpp alias/catalog labels on the old model.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Fix wording.
```

Architectural hypothesis:

```text
Model-switch guidance needs to be generated from the same config-rendering
contract as `talos setup models`, or at least from a complete canned-profile
view that knows the alias, hf_repo, and hf_file fields together.
```

Likely code/document areas:

- `SetModelCommand`
- `LlamaCppModelProfiles`
- `SetupCmd.renderManagedLlamaCppProfileConfig`
- `SetModelCommandTest`

Why a one-off patch is insufficient:

```text
This is the second T902 iteration caused by rendering/config-field mismatch.
The invariant should be "guidance includes every non-secret field required for
the selected switch path", not a manually edited prose fragment.
```

## Goal

```text
Downloaded-but-unconfigured canned-profile guidance must include all required
non-secret config fields for the switch: `llm.model`,
`engines.llama_cpp.model`, `engines.llama_cpp.hf_repo`, and
`engines.llama_cpp.hf_file`, or direct the user to the `talos setup models`
command in a way that remains actionable under path redaction.
```

## Non-Goals

- No hot-swap of managed llama.cpp GGUFs.
- No weakening render-layer path redaction.
- No automatic config write from `/set model`.
- No exposing absolute server paths in model output.

## Implementation Notes

```text
Prefer a helper that renders a minimal redaction-safe config patch from a
`LlamaCppModelProfiles.CannedProfile`. Include an explicit restart note and
keep the setup-command alternative, but do not call that alternative
copy-pasteable unless it contains no angle-bracket placeholders.
```

## Architecture Metadata

Capability:

- model switching guidance

Operation(s):

- read/catalog guidance only

Owning package/class:

- `SetModelCommand`, `LlamaCppModelProfiles`, `SetupCmd`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: installed slash-command smoke plus focused unit tests
- Verification profile: no model call required
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: n/a
- Trace/debug fields: n/a

Refactor scope:

- `<allowed: SetModelCommand guidance helper, canned-profile metadata helper, focused tests>`
- `<forbidden: llama.cpp hot-swap, path-redaction weakening, config mutation from slash command>`

## Acceptance Criteria

- `/set model <downloaded canned GGUF>` guidance includes a complete
  redaction-safe config edit for the selected profile.
- Guidance updates both model alias fields and Hugging Face source fields.
- The fallback terminal command is either genuinely copy-pasteable or clearly
  labeled as a template requiring the user's server path.
- Installed slash-command smoke shows no literal `<name>`, `<llama-server>`, or
  `[path]` in the primary config-edit path.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `SetModelCommandTest` asserts `llm.model` and
  `engines.llama_cpp.model` guidance for a mapped GGUF.
- Integration/executor test: n/a
- JSON e2e scenario: n/a
- Trace assertion: n/a

Manual/TalosBench rerun:

- Prompt family: installed `/set model gpt-oss-20b-mxfp4`
- Workspace fixture: current Talos repo
- Expected trace: n/a
- Expected outcome: complete config edit guidance

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.SetModelCommandTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- No candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.

## Known Risks

- Accidentally making the setup-command fallback look copy-pasteable while it
  still needs the user's local `server_path`.

## Known Follow-Ups

- Consider making `SetupCmd.modelsHelp()` render from the shared canned-profile
  registry so help text and switch guidance cannot drift.
