# T262 - Read-Then-Create-From-It Source Target Split
Date: 2026-05-13
Status: Done
Priority: High

## Why This Ticket Exists

The broader product audit found a real source/target accounting bug for natural source-derived artifact requests:

```text
read long-notes.txt and create ideas/summary.md from it; do not read .env.
```

Talos already handles explicit summary phrasing such as `summarize long-notes.txt into docs/summary.md`, but this more natural wording was not classified as a source-to-target artifact request.

## Evidence

- Audit directory: `local/manual-testing/broader-product-audit-20260513-155858/`
- Qwen prompt-debug transcript: `TEST-OUTPUT-PROMPT-DEBUG-QWEN-14B.txt`
- GPT-OSS prompt-debug transcript: `TEST-OUTPUT-PROMPT-DEBUG-GPT-OSS-20B.txt`
- GPT-OSS after snapshot: `WORKSPACE-PROMPT-DEBUG-GPT-OSS-20B-AFTER.txt`

Observed behavior:

- Qwen created `ideas/summary.md`, but `long-notes.txt` was also tracked as an unresolved expected mutation target.
- GPT-OSS created `ideas/summary.md` and overwrote `long-notes.txt`.
- `.env` remained protected, but the source file was not protected as an input-only source target.

## Problem

For source-derived artifact work, the input source path must be read evidence, and only the requested output path(s) should be mutation targets. Current intent parsing misses "read source and create target from it" phrasing, so `TaskContractResolver.extractExpectedTargets` falls back to all mentioned files and treats the source as mutable.

## Goal

Recognize natural read-then-create-from-it phrasing as source-to-target artifact work.

## Scope

In scope:

- Detect requests shaped like `read SOURCE and create OUTPUT from it`.
- Detect multi-output variants such as `read brief.txt and create index.html, styles.css, and scripts.js from it`.
- Set `sourceEvidenceTargets` to the source file(s).
- Set `expectedTargets` only to output file(s).
- Remove read-forbidden paths such as `.env` from expected/source targets.
- Preserve the existing source-evidence write-before-read and protected-read gates.

Out of scope:

- Broad planner rewrite.
- General multi-source synthesis beyond conservative natural source-to-output file wording.
- Changing model/provider behavior.
- Data-minimization changes for loose workspace questions like `what is in here?`.

## Acceptance

- `read long-notes.txt and create ideas/summary.md from it; do not read .env.` resolves to:
  - expected targets: `ideas/summary.md`
  - source evidence targets: `long-notes.txt`
  - no expected/source `.env`
- Prompt frame shows `requiredTargets: ideas/summary.md` and `sourceTargets: long-notes.txt`.
- A model attempt to write `long-notes.txt` for this task is blocked as an invalid/out-of-contract source mutation.
- `long-notes.txt` remains unchanged in a focused Qwen/GPT-OSS audit.
- Existing successful source-to-target and static-web-from-source paths still pass.

## Required Verification

- Unit tests for `MutationIntent.sourceToTargetArtifact`.
- Contract resolver tests for single-output and multi-output read-then-create-from-it phrasing.
- Prompt frame test for required/source target separation.
- Integration/scripted test for the GPT-OSS failure shape where the model tries to write the source file.
- Full Gradle test/build.
- Focused Qwen/GPT-OSS audit with prompt debug and `/last trace`.

## Resolution

`MutationIntent.sourceToTargetArtifact` now recognizes conservative read-then-create-from-it requests:

```text
read SOURCE and create OUTPUT from it
read SOURCE and create OUTPUT_A, OUTPUT_B from it
```

The source path is assigned to `sourceEvidenceTargets`; output paths are assigned to `expectedTargets`. This lets the existing source-evidence prompt frame, read-before-derived-write gate, expected-target validator, and static/readback verification operate on the correct roles.

The implementation is intentionally narrow: it requires a read/open/inspect source, a create/write/save/generate/make/build/scaffold output verb, explicit output file names, and a source reference such as `from it`, `using it`, or `based on it`.

## Verification

- RED:
  - `.\gradlew.bat test --tests 'dev.talos.runtime.MutationIntentTest.readThenCreateFromItSeparatesSourceAndOutputTargets' --tests 'dev.talos.runtime.MutationIntentTest.readThenCreateMultipleOutputsFromItSeparatesSourceAndOutputTargets' --tests 'dev.talos.runtime.task.TaskContractResolverTest.readThenCreateFromItSeparatesSourceEvidenceFromMutationTarget' --tests 'dev.talos.runtime.task.TaskContractResolverTest.readThenCreateMultipleOutputsFromItSeparatesSourceEvidenceFromMutationTargets' --tests 'dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest.renderSeparatesReadThenCreateFromItSourceAndRequiredTargets' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readThenCreateFromItDoesNotPermitModelToOverwriteSource'`
- GREEN:
  - same focused command passed after implementation.
- Affected suites:
  - `.\gradlew.bat test --tests 'dev.talos.runtime.MutationIntentTest' --tests 'dev.talos.runtime.task.TaskContractResolverTest' --tests 'dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming' --tests 'dev.talos.runtime.toolcall.ToolSurfacePlannerTest'`
- Full verification:
  - `.\gradlew.bat test`
  - `.\gradlew.bat build`
- Installed Talos:
  - `.\gradlew.bat installDist`
  - `pwsh .\tools\install-windows.ps1 -Force -Quiet`
  - `talos -v` reported build `2026-05-13T14:15:22.492081600Z`
- Focused live audit:
  - `local/manual-testing/t262-read-create-source-target-audit-20260513-161735/FINDINGS-T262-READ-CREATE-SOURCE-TARGET.md`
