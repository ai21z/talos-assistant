# T837 Run Command Output Handoff Boundary

Status: implemented, open for review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

T837 closes the Wave 6 high trust gap where `talos.run_command`
stdout/stderr entered model context as ordinary tool output. T834 made the
stream text sanitizer stronger, but command output still lacked an explicit
content-provenance and handoff decision.

## Implementation Summary

- `RunCommandTool` now attaches `ToolContentMetadata.COMMAND_OUTPUT` metadata
  with `ContentSource.COMMAND` to every executed command result.
- Command output whose captured streams did not require redaction remains
  eligible for model handoff, preserving normal verification usefulness.
- Command output whose captured streams required redaction is tagged
  `modelHandoffAllowed=false` and routed through
  `ToolResultModelContextHandoff`.
- Command output with bounded high-entropy stream tokens is also tagged
  `modelHandoffAllowed=false` in the command owner. This is deliberately
  command-output-specific and does not reintroduce generic high-entropy
  redaction into the universal sanitizer.
- `ToolResultModelContextHandoff` now emits a command-specific bounded notice
  instead of stdout/stderr content for redacted command streams.
- Failed or timed-out command results remain failure-dominant; the model sees
  the command outcome lead line and a withheld-output notice, not stream text.
- Context ledger entries keep the command-output boundary through
  `COMMAND_OUTPUT` / `COMMAND_PROFILE_OUTPUT` classification.

## Behavior Boundaries

T837 does not make command output generally safe. It adds a deterministic
handoff boundary for command output that already tripped the protected-content
sanitizer during capture. Normal non-sensitive output is still model-visible so
Talos can answer verification questions from command evidence.

The shipped claim is bounded:

> `run_command` stdout and stderr pass through the model-context handoff
> boundary. Non-sensitive command output remains visible to the model for
> verification answers; command output that required secret redaction is
> withheld from model context and replaced with a bounded notice. This is not a
> complete command-output privacy proof.

## Tests Added Or Extended

- `RunCommandToolTest`
  - command results carry `COMMAND_OUTPUT` metadata;
  - redacted command results are tagged for withheld model handoff.
  - high-entropy command streams are withheld, while ordinary git hashes, UUIDs,
    and prose remain model-handoff eligible.
- `ProcessCommandRunnerTest`
  - actual subprocess stdout redacts bare token, JWT, PEM private-key block, and
    JDBC connection-string fixtures before a command result is returned.
- `ToolResultModelContextHandoffTest`
  - redacted successful command output is withheld from model context;
  - redacted failed command output remains a failure while hiding stdout/stderr.
- `RunCommandOutputHandoffTest`
  - drives `talos.run_command` through `ToolCallExecutionStage`;
  - proves redacted command output is replaced in the actual model-context
    message and ledgered as withheld;
  - proves non-sensitive command output remains visible for verification.
- `TrustClaimsHonestyTest`
  - pins the new bounded public/docs claim and prevents re-broadening.

## Non-Claims

- T837 does not add arbitrary shell execution.
- T837 does not relax command profile validation, command approval, timeout
  behavior, or argv policy.
- T837 does not claim complete secret detection for all possible command
  output.
- T837 does not close T838 master-key custody.
- T837 does not edit `site/`.

## Review State

T837 remains open for review and closeout. The implementation commit SHA should
be recorded in the ticket during closeout after the full gate is reviewed.
