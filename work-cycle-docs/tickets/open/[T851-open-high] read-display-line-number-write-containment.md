# [T851-open-high] Read-Display Line-Number Write Containment

Status: open
Priority: high
Type: implementation
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

Implementation state: deterministic guard implemented; live scn-14 review
pending before closeout.

## Evidence Summary

- Source: T842 manual beta scenario evidence.
- Date: 2026-06-21.
- Talos version / commit: `0.10.5`; local summaries do not capture a commit.
- Model/backend: `gpt-oss:20b` through managed `llama.cpp`; qwen did not show
  the same corruption.
- Workspace fixture: T842 scn-14 and related scn-13 output.
- Raw transcript path: local T842 gpt-oss artifacts.
- Trace path or `/last trace` summary: local T842 gpt-oss artifacts.
- File diff summary: gpt-oss wrote line-numbered read-display text into
  `helper.py`.
- Approval choices: mutation flow was exercised.
- Verification status: readback verified bytes on disk but did not prove
  semantic correctness.

Redacted prompt sequence:

```text
Modify the requested function in helper.py.
```

Expected behavior:

```text
Display-only read_file line prefixes such as "N | " are not written back into
source files as file content.
```

Observed behavior:

```text
gpt-oss echoed read_file display formatting into an output/edit path and wrote
line-numbered content into the file during scn-14.
```

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `REPAIR_CONTROL`
- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
This can corrupt a file even though Talos remains honest about not proving
semantic correctness. It is model-specific in evidence, but the guard should be
runtime-owned.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add another warning to the model.
```

Architectural hypothesis:

```text
The runtime should reject or sanitize obvious display-only readback prefixes in
write/edit content when they match Talos's own read_file presentation format.
```

Likely code/document areas:

- `src/main/java/dev/talos/tools/impl/FileEditTool.java`
- `src/main/java/dev/talos/tools/impl/WriteFileTool.java`
- mutation evidence/readback helpers under runtime/toolcall if they already own
  display-prefix parsing.

Why a one-off patch is insufficient:

```text
The invariant applies to any model and any file write path that can receive
Talos read-display text.
```

## Goal

```text
Prevent Talos-generated read_file line-number display prefixes from being
written into files unless a future explicitly scoped path proves the user
intended literal numbered text.
```

## Non-Goals

- No semantic code verifier.
- No broad pretty-printer or formatter.
- No weakening of exact-write/readback truthfulness.
- No model-specific workaround.

## Implementation Notes

Start with a conservative guard for whole-line prefixes matching Talos
read_file display format. The guard should avoid blocking legitimate numbered
lists unless they clearly carry the `N | ` read-display pattern across multiple
source-like lines or came from same-turn read display evidence.

## Architecture Metadata

Capability:

- File write/edit safety.

Operation(s):

- write/edit.

Owning package/class:

- Tool implementation or runtime pre-execution guard, selected by current
  ownership after inspection.

New or changed tools:

- No new tool; existing `talos.write_file` / `talos.edit_file` behavior may gain
  a validation guard.

Risk, approval, and protected paths:

- Risk level: high; prevents file corruption.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: unchanged.
- Verification profile: reject display-prefix contamination before write or
  classify as failed mutation.
- Repair profile: bounded retry may ask for clean content without prefixes.

Outcome and trace:

- Outcome/truth warnings: report display-prefix contamination clearly.
- Trace/debug fields: include rejection reason where practical.

Refactor scope:

- Allowed: narrow validation guard and focused tests.
- Forbidden: broad rewrite of read_file output format or mutation verifier.

## Acceptance Criteria

- A write/edit payload containing Talos read-display prefixes is rejected or
  repaired before it reaches disk.
- Legitimate ordinary source and prose files are not falsely rejected.
- Existing file edit/write tests remain green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: write/edit tool rejects or repairs multi-line `N | ` display
  prefixes.
- Integration/executor test: gpt-oss-style payload cannot corrupt a file with
  read-display text.
- Trace assertion: rejection reason is visible where relevant.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.impl.FileEditToolTest" --tests "dev.talos.tools.impl.WriteFileToolTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Implementation Evidence

Implementation batch:

- Added package-private `ReadDisplayWriteContainmentGuard` in
  `dev.talos.runtime.toolcall`.
- Wired it into `ToolCallPreExecutionGuardChain` after named-target existence
  checks and before later write-grounding guards.
- The guard is deliberately narrow: it applies only to `write_file` content
  payloads and `edit_file` replacement payloads, only when the same target has
  same-turn `read_file` display evidence, and only when the mutation payload
  carries whole-line `N | ...` display prefixes.
- The guard records a failed pre-execution mutation outcome, emits an
  `INVALID_PARAMS` tool result, records a trace block/action-obligation event,
  and stops before approval or disk mutation.

Deterministic tests added:

- `ReadDisplayWriteContainmentGuardTest`
  - blocks `write_file` display-prefix payloads before approval;
  - blocks `edit_file` replacement display-prefix payloads before approval;
  - allows clean source writes after same-turn read display;
  - allows literal numbered-pipe text when there is no same-turn read display
    evidence for the target.
- `ToolCallLoopTest.readDisplayWritePayloadIsBlockedBeforeApprovalAndLeavesFileUnchanged`
  proves the loop-level write path leaves the file unchanged and does not ask
  for approval when the payload is contaminated by read-display line prefixes.

Focused deterministic gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ReadDisplayWriteContainmentGuardTest" --tests "dev.talos.runtime.ToolCallLoopTest.readDisplayWritePayloadIsBlockedBeforeApprovalAndLeavesFileUnchanged" --no-daemon
```

Result: PASS after the red-first failure was observed on the two blocking
cases.

Review status:

- T851 remains open.
- Required before closeout: rerun the T842/scn-14 corruption probe on both
  beta models and confirm the file is not corrupted by read-display line
  prefixes.

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version.
- Add a `CHANGELOG.md` `Unreleased` note when implementation lands.

## Known Risks

- False positives on legitimate numbered prose if the guard is too broad.

## Known Follow-Ups

- Re-run T842 scn-14 on gpt-oss after implementation.
