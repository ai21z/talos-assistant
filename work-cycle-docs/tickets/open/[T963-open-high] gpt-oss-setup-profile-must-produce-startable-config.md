# [T963-open-high] GPT-OSS Setup Profile Must Produce Startable Config

Status: open
Priority: high

## Evidence Summary

- Source: installed-product model setup and doctor release-confidence audit
- Date: 2026-07-04/05 Europe/Madrid
- Talos version / commit: 0.10.8 / 3369b237b297320915dad9dc25aa70769b2a4027
- Installed executable: `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Model/backend: `llama_cpp/gpt-oss-20b`
- Workspace fixture: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-workspaces\gptoss`
- Audit report: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\RELEASE-CONFIDENCE-SUMMARY.md`
- Failed setup output: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\gptoss\setup-model-gptoss.txt`
- Failed doctor output: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\gptoss\doctor-start.txt`
- Server log excerpt: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\gptoss\llama_cpp-18115-tail.log`
- Working direct-path setup: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\gptoss\setup-model-gptoss-direct-path.txt`
- Working direct-path doctor: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\gptoss\doctor-start-direct-path.txt`

Redacted prompt/command sequence:

```powershell
talos setup models --profile gpt-oss-20b --server-path <llama-server.exe> --write --force
talos doctor --start

# then direct local path fallback:
talos setup models --profile gpt-oss-20b --server-path <llama-server.exe> --model-path <existing gpt-oss GGUF> --write --force
talos doctor --start
```

Expected behavior:

```text
The advertised GPT-OSS setup profile writes a config that can start through
managed llama.cpp, or it clearly refuses with an actionable requirement before
writing a non-startable config.
```

Observed behavior:

```text
Setup wrote a config with empty model_path and Talos-owned hf_cache_dir. Doctor
failed to start the model. The llama.cpp log showed HEAD failed status 404 and
no remote preset found. A direct --model-path to the already downloaded GGUF in
the standard Hugging Face cache made doctor pass 8/8.
```

## Classification

Primary taxonomy bucket:

- `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `OUTCOME_TRUTH`
- `MODEL_COMPETENCE`

Blocker level:

- release blocker

Why this level:

```text
GPT-OSS is one of the release audit models. If the public setup path writes a
config that cannot start the model, first-run onboarding and the two-model
release gate are not trustworthy.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell users to pass --model-path manually.
```

Architectural hypothesis:

```text
The managed setup profile assumes llama.cpp remote model resolution will work
from repo/file metadata that does not currently start on this machine. The setup
flow needs to either resolve existing local model cache paths, download into the
configured Talos cache with a verified URL, or fail before writing a config that
doctor cannot start.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/commands/SetupCommand.java`
- model profile manifest/registry code
- setup wizard decision model from T926
- `docs/user/model-setup.md`
- installed-product smoke runbooks

Why a one-off patch is insufficient:

```text
The same setup contract applies to every advertised managed model profile. A
profile is not release-ready because a local developer can manually patch
model_path after setup.
```

## Goal

```text
`talos setup models --profile gpt-oss-20b --write` must produce a startable
managed llama.cpp config on a machine with either an existing compatible GGUF or
an allowed download path, or it must fail truthfully with exact next steps.
```

## Non-Goals

- No requiring internet access during tests unless the test is explicitly
  marked live/network.
- No silent broad filesystem scan outside reasonable model-cache locations.
- No changing the GPT-OSS model identity without a separate model-evidence
  decision.
- No hiding failure behind a successful setup message.

## Implementation Notes

Candidate directions:

- Teach setup to detect an existing compatible GGUF under the standard
  Hugging Face cache as well as the Talos-owned cache.
- Validate profile repo/file URL before writing a managed-download config.
- If neither a local file nor a verified download path exists, make setup output
  an explicit unresolved state and do not claim doctor-ready setup.
- Ensure docs and wizard copy match the actual behavior.

## Architecture Metadata

Capability:

- Managed llama.cpp model setup for GPT-OSS profile.

Operation(s):

- config write
- model file discovery
- doctor/start verification

Owning package/class:

- Setup/model profile configuration.

New or changed tools:

- No new REPL tools expected; setup CLI behavior may change.

Risk, approval, and protected paths:

- Risk level: medium/high for release, low filesystem safety risk if bounded to
  cache discovery.
- Approval behavior: CLI setup write remains explicit via `--write`; wizard
  execution remains explicit Y/N.
- Protected path behavior: do not inspect protected workspace files.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable.
- Evidence obligation: setup output must identify chosen model source.
- Verification profile: `talos doctor --start` must pass after setup or setup
  must state why it cannot.
- Repair profile: no model-loop repair.

Outcome and trace:

- Outcome/truth warnings: setup must not say the model downloads/starts if the
  configured path cannot be resolved.
- Trace/debug fields: not applicable unless setup emits structured diagnostics.

Refactor scope:

- Allowed: small model-cache resolver or profile validation helper.
- Forbidden: broad installer/wizard rewrite or new package manager dependency.

## Acceptance Criteria

- Deterministic tests cover GPT-OSS profile resolution with:
  - existing user-owned local GGUF via `--model-path`;
  - existing standard Hugging Face cache file;
  - missing file/download unresolved case.
- Setup output clearly states the selected model source or the blocking next
  step.
- `talos doctor --start` passes on the installed GPT-OSS lane after setup in the
  release audit environment.
- Docs and wizard copy match the implemented setup path.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: model profile resolver finds standard HF cache file for GPT-OSS.
- CLI test: setup writes direct `model_path` when compatible file exists.
- CLI test: setup refuses or warns before non-startable config when no model
  source can be resolved.

Manual/TalosBench rerun:

- Prompt family: installed setup/doctor smoke.
- Workspace fixture: fresh audit workspace.
- Expected trace: not applicable.
- Expected outcome: `talos doctor --start` 8/8 for GPT-OSS after setup.

Commands:

```powershell
.\gradlew.bat test --tests "*Setup*" --tests "*Model*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Behavior-changing closeout requires a CHANGELOG entry under `## [Unreleased]`.
- Release artifacts remain blocked until the GPT-OSS installed setup/doctor lane
  is clean or the public beta explicitly removes GPT-OSS from advertised setup.

## Known Risks

- Standard Hugging Face cache layouts can vary. Keep discovery bounded and
  testable; do not crawl arbitrary user directories.

## Known Follow-Ups

- Publish a model evidence matrix once setup and doctor behavior is stable.
