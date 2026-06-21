# T851 Read-Display Line-Number Write Containment

Status: done
Date: 2026-06-21
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Summary

T851 adds a narrow pre-approval containment guard for a T842 manual-audit
failure where GPT-OSS wrote Talos `read_file` display text such as
`1 | def bar():` back into a source file.

This is a runtime-owned mutation-boundary fix. It does not change `read_file`
display formatting, semantic verification, approval policy, or file tool disk
write semantics.

## Boundary

Moved into production:

- `ReadDisplayWriteContainmentGuard` in `dev.talos.runtime.toolcall`.
- A `ToolCallPreExecutionGuardChain` call site that blocks contaminated
  mutation payloads before approval.

Not moved or changed:

- `ReadFileTool` output shape.
- `FileWriteTool` and `FileEditTool` disk write behavior.
- Approval gate semantics.
- Existing exact-write/readback verification.
- T850 read-only grounding and T852 GPT-OSS synthesis behavior.

## Guard Semantics

The guard fires only when all of these are true:

- the tool is `write_file` or `edit_file`;
- the target path has same-turn `read_file` evidence with Talos display lines;
- the mutation payload carries whole-line `N | ...` read-display prefixes.

When it fires, the chain:

- increments failure accounting;
- emits an `INVALID_PARAMS` tool result with a bounded diagnostic;
- records `READ_DISPLAY_WRITE_CONTAINMENT`;
- records the blocked tool call in the local trace;
- adds a failed pre-execution mutation outcome;
- appends the formatted tool result back to the model context;
- returns without approval and without disk mutation.

The guard intentionally does not block literal numbered-pipe text without
same-turn read-display evidence.

## Deterministic Coverage

Added:

- `ReadDisplayWriteContainmentGuardTest`
  - `writeFileBlocksSameTurnReadDisplayPrefixesBeforeApproval`;
  - `editFileBlocksSameTurnReadDisplayReplacementBeforeApproval`;
  - `ordinarySourceWriteIsAllowedAfterSameTurnReadDisplay`;
  - `literalNumberedPipeTextWithoutSameTurnReadDisplayIsAllowed`.
- `ToolCallLoopTest.readDisplayWritePayloadIsBlockedBeforeApprovalAndLeavesFileUnchanged`.

Red-first result:

- Initial focused run failed on the two blocking tests because the chain allowed
  the contaminated payloads.

Focused green result:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ReadDisplayWriteContainmentGuardTest" --tests "dev.talos.runtime.ToolCallLoopTest.readDisplayWritePayloadIsBlockedBeforeApprovalAndLeavesFileUnchanged" --no-daemon
```

Result: PASS.

## Live Closeout Evidence

Implementation commit: `34f8f832377e6fdd5bd26141faf2b1b972cfb8da`.

Installed build refresh:

```powershell
.\gradlew.bat installDist --no-daemon
```

Result: PASS.

Existing T842/scn-14 absent-target rerun on the current installed build:

- `qwen2.5-coder-14b` through managed `llama.cpp`:
  - transcript:
    `local/beta-pre-release-test-scenarios/runs/t851-34f8f832/qwen2.5-coder-14b/scn-14-anti-overclaim-live/transcript.txt`
  - final `helper.py`: unchanged, `def bar(): return 1`
  - `git-status.txt`: empty
  - outcome: FAILED honestly because the named target `foo()` did not exist;
    no approval and no mutation.
- `gpt-oss-20b` through managed `llama.cpp`:
  - transcript:
    `local/beta-pre-release-test-scenarios/runs/t851-34f8f832/gpt-oss-20b/scn-14-anti-overclaim-live/transcript.txt`
  - final `helper.py`: unchanged, `def bar(): return 1`
  - `git-status.txt`: empty
  - outcome: FAILED honestly because the named target `foo()` did not exist;
    no approval and no mutation.

This confirms the original scn-14 corruption fixture no longer corrupts the
file on either beta model. In this fixture, T849's absent named-target guard is
the first blocking guard, so the rerun is a combined beta-correctness gate rather
than direct evidence that T851 fired.

Direct target-present read-display corruption probe on the current installed
build:

- Prompt asked Talos to read `helper.py`, then modify existing `bar()` to return
  `99`, while explicitly requesting the model to copy the line-numbered
  `read_file` display format back into the file.
- `qwen2.5-coder-14b`:
  - transcript:
    `local/beta-pre-release-test-scenarios/runs/t851-34f8f832/qwen2.5-coder-14b/t851-target-present-read-display-probe/transcript.txt`
  - result: clean `edit_file`; final `helper.py` changed to `def bar(): return
    99` with no `N |` prefixes.
  - interpretation: the model ignored the poisoned line-prefix instruction, so
    the T851 guard did not need to fire.
- `gpt-oss-20b`:
  - transcript:
    `local/beta-pre-release-test-scenarios/runs/t851-34f8f832/gpt-oss-20b/t851-target-present-read-display-probe/transcript.txt`
  - trace:
    `local/beta-pre-release-test-scenarios/runs/t851-34f8f832/gpt-oss-20b/t851-target-present-read-display-probe/home/.talos/sessions/traces/997c01313b2dc5b19d406c969c5f58c98b2ed569-20260621214555/000001-trc-a687b931-e8cc-4011-8fca-7080bfc8269a.json`
  - result: first contaminated `write_file` call was blocked with
    `READ_DISPLAY_WRITE_CONTAINMENT` / `READ_DISPLAY_PREFIX_WRITE`; the model
    then retried with clean content, and final `helper.py` changed to
    `def bar(): return 99` with no `N |` prefixes.
  - interpretation: this directly exercises the T851 guard against the
    GPT-OSS corruption class and proves the poisoned payload does not reach
    disk.

Closeout decision: accepted. The original scn-14 live gate is no-corruption on
both beta models, and the direct target-present probe proves the T851 guard
fires for the model and payload class that originally corrupted a file.
