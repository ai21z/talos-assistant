# Model qualification runbook

Use this runbook after candidate research and before any profile or hardware support claim. It executes the lifecycle documented in the repository without weakening Talos policy.

Machine-readable JSON is authoritative. Markdown guides and reports help reviewers, but they do not replace campaign, run, trace, approval, byte, timing, or resource records.

## Preconditions

- the working tree is clean
- the branch and exact Talos commit are recorded
- the candidate artifact or controlled conversion is frozen
- local SHA-256 and size match the candidate packet
- the qualification harness has no unresolved blocking defect
- the pinned llama.cpp executable and its SHA-256 are available
- the scenario-bank hash is recorded
- the test workspace and `TALOS_HOME` are isolated
- enough disk remains for transcripts, traces, logs, snapshots, and reports

Do not start a model trial while any identity fact is unknown.

## Local layout

Keep evidence under:

```text
local/model-qualification/<campaign-id>/
  campaign.json
  calibration-rules.json
  calibration-guide.md
  candidates/<alias>/candidate.json
  machines/<machine-id>.json
  engines/<lane-id>.json
  runs/<candidate>/<machine>/<config>/<scenario>/<repetition>/
  scorecards/
```

Keep model weights and conversion intermediates in ignored model storage. Keep reviewer summaries in ignored `reports/`.

## Gate 0, verify identity

Record and verify:

1. branch, commit, version, and clean status
2. built and installed Talos artifact hashes when testing the installed product
3. candidate repository, revision, file, size, SHA-256, and license
4. conversion lineage when the GGUF is locally generated
5. llama.cpp tag, source commit, executable SHA-256, backend, and driver
6. machine identity and available resources
7. protocol revision and scenario-bank hash

Stop if any value differs from the planned packet.

## Gate 1, freeze calibration

Complete the calibration JSON and reviewer guide before trial one. Hash both into `campaign.json`.

The calibration records model, engine, machine, launch arguments, context, tool mode, reasoning, template, sampling, output caps, timeout, seed policy, permitted deviations, stop rules, and evidence requirements.

Once the first trial starts, calibration is immutable. A material change creates a new campaign or configuration identity.

## Gate 2, direct engine preflight

Start the exact engine outside Talos when model compatibility is not already proven.

Capture:

- full launch command and environment
- load and model metadata lines
- `/props` and `/v1/models`
- no-tool response
- automatic tool response
- required tool response
- multi-argument tool response
- reasoning and modality behavior
- engine shutdown and process cleanup

Classify native handler and generic template behavior separately. Text-tool mode is a different configuration.

Stop on wrong identity, unsupported template, required projector, reasoning mismatch, malformed call, unsupported required-tool behavior, or engine patch requirement.

## Gate 3, run harness checks

Before live trials:

```powershell
.\gradlew.bat modelQualificationBankHash --no-daemon
.\gradlew.bat validateModelQualificationCalibration --no-daemon
```

Run focused qualification tests when harness code changed. Run the full check before relying on a changed harness.

The hash emitted by `modelQualificationBankHash` must equal the value frozen in the campaign.

## Gate 4, run admission

Admission contains 12 admission trials, one fresh trial per declared scenario.

Capture each trial through the true PTY task. Use a fresh workspace and the declared isolated home. Save transcript, prompt debug, trace, provider or model log, approvals, before and after bytes, timings, resources, canary result, and mechanical result.

After every trial, run continuation policy. Stop the campaign on invalid evidence, incomplete evidence, trust failure, Talos runtime defect, runtime failure, or hardware failure.

Admission is a filter. Do not project a profile from admission alone.

## Gate 5, run full qualification

Start a new immutable campaign after admission passes. Do not copy admission runs.

Full qualification contains 72 full trials, 24 scenarios with three repetitions each. Use the declared repetition order. Do not rerun only failed responses and do not replace an interrupted trial without invalidating the packet.

Review evidence as it is captured. A late discovery that invalidates the shared configuration invalidates the complete campaign.

## Gate 6, finalize and score

Run the maintained requirement and approval finalizers. Then validate and score the packet.

```powershell
.\gradlew.bat finalizeModelQualificationRequirements --no-daemon
.\gradlew.bat finalizeModelQualificationApprovals --no-daemon
.\gradlew.bat validateModelQualificationEvidence --no-daemon
.\gradlew.bat scoreModelQualificationEvidence --no-daemon
```

Use the task arguments required by the current Gradle help output. Preserve commands and outputs in the packet.

The scorecard records exact counts, denominators, failure classes, trust verdict, performance envelope, and disposition. Reviewer preference is non-gating.

## Gate 7, validate hardware separately

Run physical hardware packets only for a candidate with a selectable model-quality disposition.

Use a new machine and configuration identity for every OS and backend. Measure cold and warm load, first token, rates, process and system memory, pagefile or swap, page faults where available, VRAM, offload, context fit, and stability.

Do not assign Low, Medium, or High from reference-host qualification.

## Stop the campaign

Stop the campaign immediately for:

- unapproved or out-of-scope mutation
- false completion or verification claim
- protected-content disclosure
- read-evidence cross-binding
- hidden timeout, cap hit, cancellation, denial, or malformed response
- selected, configured, served, or advertised identity disagreement
- missing mandatory evidence
- calibration drift
- runtime or hardware instability

A Talos defect is not a model failure. Record it separately, add a failing regression test, fix it, and start a fresh campaign identity.

## Closure packet

Admission closure requires 12 valid trials, calibration hashes, candidate and engine hashes, validator output, scorecard, and a pass or mechanical rejection.

Full closure requires 72 valid trials, both finalizers, validator output, complete scorecard, continuation record, performance envelope, and disposition review.

Hardware closure requires one complete physical packet per claimed OS and backend. The claim must not be broader than measured machines.

Before profile projection, confirm that public documentation, setup, doctor, status, model listing, prompt-visible tools, runtime-permitted tools, and trace all describe the same reviewed profile.
