# T739 - Workspace-Obligation Provider Tool Choice

Status: done - completed in wave 1; see completion evidence section
Severity: high
Release gate: yes - directly unblocks the Qwen full synchronized bank (T280/T284/T312)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

`WORKSPACE_OPERATION_REQUIRED` turns are the only mutation-obligation family
sent to the provider with `tool_choice: AUTO`. Under llama.cpp `--jinja`, AUTO
means the tool-call grammar engages only lazily after a trigger token, so
qwen2.5-coder-14b was free to emit malformed protocol debris (bank run 1) or
plain prose with no tool call at all (bank run 3) on exactly the
`workspace-batch-apply-approved` scenario. Three full-bank release-gate
failures trace primarily to this gap.

## Evidence Analysis

- `src/main/java/dev/talos/runtime/policy/ProviderRequestControlPolicy.java:16`
  defines `MUTATING_TOOLS = Set.of("talos.write_file", "talos.edit_file")` —
  none of the six workspace tools are present.
- The obligation switch (lines 41-65) maps `EXPLICIT_COMMAND_REQUEST`,
  `CONDITIONAL_REVIEW_FIX`, `MUTATING_TOOL_REQUIRED`,
  `REPAIR_FROM_VERIFIER_FINDINGS`, `INSPECT_REQUIRED`, `VERIFY_FROM_EVIDENCE`,
  `LIST_DIR_ONLY`, and evidence obligations to `ToolChoiceMode.REQUIRED` —
  there is **no branch for `ActionObligation.WORKSPACE_OPERATION_REQUIRED`**
  (enum at `runtime/policy/ActionObligation.java:10`), so the default
  `ChatRequestControls.defaults()` (AUTO) is returned.
- All three failing provider bodies lack a `tool_choice` field
  (r1: `current-0.10.1-release-packet-20260610-090049/artifacts/qwen/sync-approval/workspace-batch-apply-approved/provider-bodies/provider-body.json`;
  r3: `current-0.10.1-qwen-syncbank-r3-20260610-210541/artifacts/workspace-batch-apply-approved/provider-bodies/provider-body.json`);
  prompt-debug records `Tool choice: AUTO`.
- The transport is fully ready: `LlamaCppEngine.java:39-52` declares
  `requiredToolChoice=true` and `namedToolChoice=true`;
  `CompatChatClient.java:248-263` serializes both `"required"` and the named
  function shape. Eight repair planners already use REQUIRED. T109 shipped this
  exact mechanism for `MUTATING_TOOL_REQUIRED` — which passes the bank.
- The failing scenario exposes exactly one tool
  (trace `TOOL_SURFACE_SELECTED {nativeToolCount=1}`), so NAMED is available
  as the strongest constraint.

## Architectural Hypothesis

Runtime-owned constraint-coverage gap: T109's obligation-to-provider-control
mapping predates the workspace-operation tool family and was never extended.
This is wiring, not new infrastructure.

## Architecture Metadata

Capability: provider request control (tool choice) for workspace operations
Operation(s): mkdir, copy_path, move_path, rename_path, delete_path, apply_workspace_batch
Owning package/class: `dev.talos.runtime.policy.ProviderRequestControlPolicy`
New or changed tools: none (request-control policy only)
Risk, approval, and protected paths:
  - Risk level: none added; approval/permission gates unchanged
  - Approval behavior: unchanged (constraint applies to model output shape only)
  - Protected path behavior: unchanged
Checkpoint, evidence, verification, and repair:
  - Checkpoint behavior: unchanged
  - Evidence obligation: unchanged
  - Verification profile: unchanged
  - Repair profile: unchanged (T743 extends separately)
Outcome and trace:
  - Outcome/truth warnings: unchanged
  - Trace/debug fields: debug tags record the selected obligation + tool choice
Refactor scope: ProviderRequestControlPolicy + its test only

## Required Behavior

- Add a `WORKSPACE_TOOLS` set: `talos.apply_workspace_batch`, `talos.mkdir`,
  `talos.copy_path`, `talos.move_path`, `talos.rename_path`,
  `talos.delete_path`. Do **not** widen `MUTATING_TOOLS` (it drives other
  branches).
- When `ActionObligation.WORKSPACE_OPERATION_REQUIRED` is active, the backend
  supports required tool choice, and at least one workspace tool is visible:
  return `ToolChoiceMode.REQUIRED` with debug tag
  `action-obligation:WORKSPACE_OPERATION_REQUIRED`.
- When exactly one workspace tool is visible, return `ToolChoiceMode.NAMED`
  with that tool name (strongest grammar constraint).
- Direct-answer turns and unsupported backends keep current AUTO behavior.

## Non-Goals

- No schema changes to the batch tool (T744).
- No sampling changes (T740).
- No repair-ladder changes (T743).

## Tests

- `ProviderRequestControlPolicyTest` additions mirroring T109 style:
  - workspace obligation + multiple workspace tools visible → REQUIRED + tag;
  - workspace obligation + exactly one workspace tool visible → NAMED with
    that tool;
  - workspace obligation + unsupported backend → AUTO preserved;
  - direct answer turn → AUTO preserved;
  - MUTATING_TOOL_REQUIRED behavior unchanged (regression).

## Acceptance Criteria

- Focused test run green:
  `./gradlew.bat test --tests "dev.talos.runtime.policy.ProviderRequestControlPolicyTest" --no-daemon`.
- Provider body for a workspace-obligation turn contains `tool_choice`
  (`"required"` or named shape) — assertable via CompatChatClient body test or
  live provider-body capture in T746.
- CHANGELOG `## [Unreleased]` gains a T739 entry.
- No behavior change for non-workspace obligations.

## 2026-06-10 completion evidence

- Implemented: `WORKSPACE_TOOLS` set + `WORKSPACE_OPERATION_REQUIRED` branch
  with NAMED single-tool pinning in `ProviderRequestControlPolicy` (new 4-arg
  `forTurn` overload; 3-arg delegates with named unsupported, so all prior
  callers/tests are unchanged); `LlmClient.supportsNamedToolChoice()`
  mirroring the required-choice accessor;
  `AssistantTurnExecutor.chatControlsForTurn` passes both capability flags.
- `./gradlew.bat test --tests "dev.talos.runtime.policy.ProviderRequestControlPolicyTest" --no-daemon`
  PASS — 10 tests (6 existing regression + 4 new), including precondition
  asserts proving the batch prompt classifies as
  `WORKSPACE_OPERATION_REQUIRED`.
- Live provider-body confirmation (tool_choice present on a workspace turn)
  is deferred to the T746 stabilization banks.
