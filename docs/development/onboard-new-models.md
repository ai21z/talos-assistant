# Onboard new models

This guide explains how a model becomes a Talos profile. It is for maintainers evaluating local GGUF models against Talos tools, approvals, verification, and runtime limits.

Read [Model profile lifecycle](../architecture/model-profile-lifecycle.md) before starting. The lifecycle separates evidence progress, product disposition, and hardware support. The [Model profiles](../reference/model-profiles.md) page remains the public list of selectable profiles.

Upstream benchmarks select candidates. They do not promote Talos profiles.

## Choose a candidate

Start with a concrete Talos role, not a leaderboard rank.

Good candidate questions are:

- Can this model provide a smaller reliable coding lane?
- Can it improve difficult multi-file tasks without weakening abstention?
- Does it provide a clean native tool template on the pinned engine?
- Does its license allow public open-source use?
- Is there a reproducible official artifact or source conversion path?

Prefer primary model cards, official repositories, official engine documentation, and reproducible local measurements. Community reports help find candidates, but they do not establish Talos support.

Reject the candidate before download when its license, provenance, serving requirements, or product role are unclear.

## Check license and provenance

Record:

- official repository and full revision
- license and any use restrictions
- exact artifact or every source weight shard
- published size and LFS SHA-256
- architecture and active or total parameters
- official chat template and reasoning behavior
- declared context and serving requirements
- GGUF producer when the artifact is not official

Do not treat a repository name, filename, or model-family name as byte identity.

## Freeze the artifact

Download into ignored local storage through a revision-pinned URL. Use a partial filename until size and SHA-256 match. Keep source weights, model files, conversion intermediates, and packets out of the tracked repository.

For an official GGUF, record expected and actual size and SHA-256 independently.

For a controlled conversion, record every source hash, converter source hash, dependency version, command, quantizer hash, intermediate hash, and final hash. Repeat the conversion or otherwise prove the final output is equivalent.

Write `candidate.json`, then hash it. Later campaigns reference that hash.

## Run compatibility preflight

Use the exact pinned llama.cpp executable intended for qualification. Capture its tag, commit, executable SHA-256, backend, driver, and full launch command.

Preflight checks:

1. the engine loads the exact artifact
2. `/props` and `/v1/models` report the expected model
3. the embedded or selected template matches the calibration
4. text-only candidates do not depend on an unowned projector
5. reasoning behavior matches the declared mode
6. native or text-tool mode is labeled accurately
7. no-tool, automatic-tool, required-tool, and multi-argument calls behave as declared

A successful health request is not compatibility proof. A successful chat response is not tool proof.

## Freeze calibration

Create `calibration-rules.json` and `calibration-guide.md` before the first trial. The JSON record is authoritative. Hash both files into `campaign.json`.

Calibration fixes:

- model and candidate packet
- Talos commit and installed bytes
- qualification protocol and scenario-bank hash
- engine and executable hash
- machine identity
- launch arguments
- context, template, reasoning, and tool mode
- sampling and seed policy
- output caps and timeout
- allowed deviations
- stop conditions

A material change creates a new configuration identity. Do not overwrite or repair a packet after trials begin.

## Run admission

Admission uses 12 fixed scenarios, one fresh trial each. It covers abstention, reads, mutations, denial, preservation, repair, and interruption.

Admission must have complete evidence and zero Talos trust failures. It is a compatibility and utility filter. Passing admission is not a support claim.

If a Talos defect appears, stop the campaign. Write a focused failing test, fix the defect, run the full relevant gate, and create a new calibration identity. Do not reuse earlier trials.

## Run full qualification

Full qualification uses 24 scenario definitions across user archetypes. Run each scenario three times for 72 fresh trials under one immutable configuration.

The mechanical scorer decides accepted beta stability, experimental selectable, rejected, blocked trust, or blocked incomplete. Do not change thresholds after seeing a model's outputs. Do not select only favorable repetitions.

Reviewers finalize requirements and approvals from captured evidence. Machine-readable JSON remains authoritative over Markdown summaries.

## Validate physical hardware

Run hardware validation only after a model earns a selectable quality disposition.

Create a new packet for every claimed OS, backend, and hardware class. Record CPU, RAM, storage, GPU, VRAM, driver, power mode, pagefile or swap, context, offload, and complete server arguments.

Measure cold load, warm load, first-token latency, prompt and generation rates, process RAM, system RAM, pagefile or swap growth, page faults where available, VRAM, offload, context fit, and repeated stability.

A constrained virtual machine is preliminary evidence. It does not establish physical memory bandwidth, PCIe behavior, storage, thermals, or driver behavior.

Model quality and hardware support are separate decisions.

## Project a profile

Project only reviewed evidence into `LlamaCppModelCatalog`.

The catalog entry pins:

- repository, revision, artifact, size, SHA-256, and license
- architecture and parameter shape
- tool mode, reasoning mode, and template
- context, sampling, and managed server arguments
- compatible engine lanes
- measured hardware guidance
- disposition and selectability
- protocol revision, packet, Talos commit, scorecard hash, and date

After projection, test setup, artifact verification, status, doctor, banner, model listing, prompt-visible tools, runtime-permitted tools, trace, and documentation. A catalog entry must not claim more than the packet proved.

## Demote or retire a profile

Reopen review when bytes, engine, template, reasoning, tool schema, context, sampling, timeout, or trust policy changes.

Demote when current evidence no longer supports the recorded capability. Remove the profile from normal selection when it is superseded, unavailable, legally unusable, or no longer maintainable. Preserve the reason in maintained release history.

`retired` is a lifecycle outcome today, not a current catalog enum. Do not invent a runtime disposition in documentation.

## Evidence storage

Keep local campaigns under `local/model-qualification/<campaign-id>/`. Keep reviewer reports under ignored `reports/`. Keep weights and conversion work under ignored local model storage.

Tracked docs explain the repeatable process. They do not contain raw transcripts, private machine paths, ticket histories, or model weights.

Repository maintainers follow the tracked qualification runbook and templates for the execution sequence and evidence checks.
