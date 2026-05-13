# T254 - Source File Artifact Builds Need Read-Source / Write-Target Splitting
Date: 2026-05-12
Status: Done
Priority: High

## Why This Ticket Exists

The model setup two-model audit found that Talos treated a source brief as a
required mutation target:

```text
make a real static landing page from rough-brief.txt. use index.html styles.css scripts.js. do not use script.js.
```

Prompt construction injected:

```text
requiredTargets: rough-brief.txt, index.html, styles.css, scripts.js
```

`rough-brief.txt` should be read-only source evidence, not a file to mutate.

Evidence:
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 6862-6997.
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 7514-7605.
- Final GPT-OSS workspace has `rough-brief.txt` mutated into release content and `index.html` left as `[placeholder]`.

## Problem

`TaskContractResolver.extractExpectedTargets(...)` collects all file mentions in
mutation requests. For artifact build prompts, not every file mention is a write
target.

Talos already has source-to-target handling for summary requests through
`MutationIntent.sourceToTargetArtifact(...)`, but it does not handle build-from
source artifact phrasing such as:

```text
make a page from rough-brief.txt using index.html styles.css scripts.js
build a report from notes.txt as report.md
create a website from brief.txt with index.html styles.css scripts.js
```

## Goal

When a user asks Talos to build an artifact from a source file, the source file
must become source evidence and the requested output files must become expected
mutation targets.

## Scope

In scope:
- Extend deterministic contract resolution for build-from-source artifact prompts.
- Exclude source files introduced by `from <file>` from expected mutation targets when output targets are present.
- Add tests for static web and document-style artifact prompts.
- Preserve `do not use script.js` forbidden-target behavior.

Out of scope:
- General planner.
- Model prompt wording changes beyond current-turn frame data generated from the corrected contract.
- PDF/DOCX generation support.

## Acceptance

- The audit prompt resolves with source evidence target `rough-brief.txt`.
- Expected targets are exactly `index.html`, `styles.css`, and `scripts.js`.
- Forbidden targets contain `script.js`.
- Static verifier no longer expects source evidence files to be mutated.
- Tests cover `script.js` vs `scripts.js` spelling.

## Required Verification

- Unit tests for build-from-source target splitting.
- Static verifier tests proving source evidence files are exempt from expected mutation checks.
- Scripted static-web scenario proving the source file is read but not mutated.
- Focused two-model audit coverage before closing the milestone batch.

## Resolution

Implemented deterministic build-from-source source/target splitting in
`MutationIntent.sourceToTargetArtifact(...)`:

- `create/build/make ... from <source> with <outputs>` now treats the source
  file as source evidence and the listed files as mutation targets;
- `build ... from <source> as <output>` now supports single-output document
  artifacts such as `report.md`;
- forbidden target handling still removes `script.js` while preserving
  `scripts.js`.

Added contract tests for the missing prompt shapes and a scripted assistant turn
test proving that a static web build reads the source brief, writes only the
requested output files, leaves the source file unchanged, and does not create
the forbidden singular `script.js`.

## Verification

- `.\gradlew.bat test --tests 'dev.talos.runtime.task.TaskContractResolverTest.staticWebBuildFromSourceWithOutputsSeparatesSourceEvidenceFromOutputTargets' --tests 'dev.talos.runtime.task.TaskContractResolverTest.documentBuildFromSourceAsSingleOutputSeparatesSourceEvidenceFromOutputTarget'`
- `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.staticWebBuildFromSourceReadsBriefAndDoesNotMutateSource'`
- `.\gradlew.bat test --tests 'dev.talos.runtime.MutationIntentTest' --tests 'dev.talos.runtime.task.TaskContractResolverTest' --tests 'dev.talos.runtime.verification.StaticTaskVerifierTest' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.staticWebBuildFromSourceReadsBriefAndDoesNotMutateSource' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.summarizeSourceIntoFileReadsSourceThenWritesTarget' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.summarizeSourceIntoFileWithoutSourceReadDoesNotCreateUngroundedArtifact'`
- `.\gradlew.bat test`
- `.\gradlew.bat build`
- `.\gradlew.bat installDist`
- `pwsh .\tools\install-windows.ps1 -Force`
- Focused two-model audit:
  `local/manual-testing/t254-source-target-splitting-audit-20260513-153310/FINDINGS-T254-SOURCE-TARGET-SPLITTING.md`

Audit conclusion:

- Qwen and GPT-OSS both received correct prompt-debug frames and traces for
  source/target separation.
- Qwen read `brief.txt`, then wrote `index.html`, `styles.css`, and
  `scripts.js`; `script.js` was not created.
- GPT-OSS initially attempted `index.html` before reading `brief.txt`; the
  source-evidence gate blocked that write before approval, then the model read
  `brief.txt` and wrote the requested output files.
- Both models read `notes.txt` before writing `report.md`.
- Static web output quality still failed verification in the live audit, but
  the runtime classified and reported that as incomplete/partial instead of
  claiming success. That is not a T254 source-target splitting failure.
