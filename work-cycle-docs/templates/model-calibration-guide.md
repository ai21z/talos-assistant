# Model calibration guide

Use this guide beside the authoritative `calibration-rules.json`. Complete it before the first model trial. Replace every placeholder.

## Configuration identity

- Campaign ID: `<campaign-id>`
- Configuration ID: `<config-id>`
- Candidate alias: `<alias>`
- Qualification protocol: `<revision>`
- Scenario-bank SHA-256: `<sha256>`
- Talos commit: `<commit>`
- Talos version: `<version>`
- Installed artifact SHA-256: `<sha256>`

## Model identity

- Official repository: `<owner/repository>`
- Frozen revision: `<commit>`
- Artifact: `<filename>`
- Local path: `<ignored-local-path>`
- Published size: `<bytes>`
- Actual size: `<bytes>`
- Published SHA-256: `<sha256>`
- Actual SHA-256: `<sha256>`
- License: `<license>`
- Architecture: `<architecture>`
- Parameters: `<active-and-total>`
- GGUF provenance: `<official-or-conversion-lineage>`

## Engine identity

- llama.cpp tag: `<tag>`
- llama.cpp commit: `<commit>`
- Asset: `<asset>`
- Executable SHA-256: `<sha256>`
- Backend: `<cpu-cuda-or-other>`
- Driver: `<driver-or-not-applicable>`
- Full launch arguments: `<arguments>`
- Chat template: `<template>`
- Handler: `<native-generic-or-text-tool>`

## Runtime policy

- Tool mode: `<native-or-text-tool>`
- Reasoning mode: `<mode>`
- Context: `<tokens>`
- Parallel slots: `<count>`
- Server output backstop: `<tokens>`
- Read output cap: `<tokens>`
- Mutation output cap: `<tokens>`
- Request timeout: `<seconds>`
- Sampling policy: `<values-and-where-they-apply>`
- Seed or repetition policy: `<policy>`

## Machine identity

- Machine ID: `<machine-id>`
- OS and build: `<os>`
- CPU: `<model-cores-threads>`
- RAM: `<capacity-and-layout>`
- Storage: `<type>`
- GPU: `<model-or-none>`
- VRAM: `<capacity-or-none>`
- Power mode: `<mode>`
- Pagefile or swap: `<policy>`

## Standard and tuned configurations

State whether this is the standard Talos configuration or a separately tested tuned configuration.

- Kind: `<standard-or-tuned>`
- Baseline configuration ID: `<id-or-not-applicable>`
- Changed variable: `<one-declared-change-or-none>`
- Reason for separate identity: `<reason>`

Never overwrite the standard result with tuned evidence.

## Model-specific risks

- Expected tool-call risks: `<risks>`
- Expected reasoning risks: `<risks>`
- Expected template risks: `<risks>`
- Expected context or cap risks: `<risks>`
- Expected hardware risks: `<risks>`
- Known unsupported modalities: `<limits>`

## Admission rules

- Required trust gates: `<rules>`
- Utility threshold: `<rules>`
- Stop conditions: `<rules>`
- Required evidence: `<artifacts>`

## Permitted deviations

List every permitted deviation and its effect on comparability.

| Deviation | Allowed value | Why allowed | Comparison impact |
| --- | --- | --- | --- |
| `<name>` | `<value>` | `<reason>` | `<impact>` |

Any unlisted material deviation invalidates the configuration identity.

## Evidence locations

- Candidate packet: `<path>`
- Machine record: `<path>`
- Engine record: `<path>`
- Runs: `<path>`
- Scorecard: `<path>`
- Reviewer report: `<path>`

## Calibration hashes

- `calibration-rules.json` SHA-256: `<sha256>`
- `calibration-guide.md` SHA-256: `<sha256>`
- Hashes recorded in `campaign.json`: `<yes-or-no>`
