# T741 - Named Tool Choice On Source-Evidence Repair Re-Prompts

Status: open
Severity: medium
Release gate: supports T280/T284 bank stability (t325-class failures)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

When the source-evidence gate blocks a derived write and re-prompts with an
explicit instruction ("Call talos.read_file for the missing source target(s)
first"), the re-prompt still leaves the model free tool choice. In bank run 2,
qwen answered that instruction with `talos.grep` four times until the failure
policy stopped the scenario (`t325-python-command-boundary`, fail-closed after
24 completed scenarios).

## Evidence Analysis

- r2 trace (`.../artifacts/qwen/sync-approval-r2/t325-python-command-boundary/traces/last-trace.txt`):
  `SOURCE_EVIDENCE_BEFORE_DERIVED_WRITE FAILED` ×4 with the explicit
  "Call talos.read_file first" diagnostic; only `TOOL_CALL_PARSED talos.grep`
  between blocks; then fail-closed stop.
- `ProviderRequestControlPolicy.java:67-68`: `namedTool` is always `""` —
  NAMED is never used anywhere despite `LlamaCppEngine.java:47`
  (`namedToolChoice=true`) and `CompatChatClient.java:254-261` serializing the
  correct OpenAI named-function shape.
- The repair planner that owns this re-prompt:
  `runtime/toolcall/SourceEvidenceReadBeforeWriteRepairPlanner.java` — builds
  REQUIRED controls at line 136 and the repair frame (`repairMessages`,
  105-126) that names both the required tool and the exact missing path.
- When the runtime already knows the exact tool and target, free choice is
  gratuitous risk for a 14B model; pinning shrinks the task to one argument.

## Architectural Hypothesis

The repair planners are the right owner: they know the required tool by name
at construction time. No policy inference needed — pass NAMED explicitly.

## Architecture Metadata

Capability: provider request control on repair re-prompts
Operation(s): source-evidence read-before-write repair (and sibling
source-evidence planners if they share the pattern)
Owning package/class: `dev.talos.runtime.toolcall.SourceEvidenceReadBeforeWriteRepairPlanner`
(+ `SourceEvidencePostReadWriteRepairPlanner`, `SourceEvidenceExactRepairPlanner`
where the required tool is known)
New or changed tools: none
Risk, approval, and protected paths: unchanged
Checkpoint, evidence, verification, and repair:
  - Repair profile: bounded repair unchanged; only the constraint envelope tightens
Outcome and trace:
  - Trace/debug fields: debug tags record `named-tool:talos.read_file`
Refactor scope: the named planners + tests

## Required Behavior

- `SourceEvidenceReadBeforeWriteRepairPlanner` (and siblings where the
  required tool is unambiguous) construct controls with
  `ToolChoiceMode.NAMED`, `namedTool="talos.read_file"`, and (after T740)
  `SamplingControls.NEAR_GREEDY`.
- Fallback: if the backend lacks named support, use REQUIRED (current
  behavior).

## Non-Goals

- No changes to the gate itself (`SourceDerivedEvidenceGuard`) or its
  diagnostics.
- No changes to MissingMutationRetry (T743).

## Tests

- Planner unit tests asserting NAMED + tool name + (post-T740) near-greedy
  sampling in the built controls; REQUIRED fallback when unsupported.
- `CompatChatClientTest` named-shape serialization (may already exist —
  extend if not).

## Acceptance Criteria

- Focused planner/client tests green.
- t325-style repair re-prompt provider body contains the named function shape
  (asserted live in T746).
- CHANGELOG `## [Unreleased]` gains a T741 entry.
