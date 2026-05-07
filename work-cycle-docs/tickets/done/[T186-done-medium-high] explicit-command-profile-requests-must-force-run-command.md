# T186 - Explicit Command-Profile Requests Must Force `talos.run_command`

Status: done
Severity: medium/high

## Problem

T61-M showed a remaining command workflow failure for GPT-OSS under managed llama.cpp.

The user asked:

> Run the approved Gradle test command profile for this workspace and report the exact command result. Do not invent a pass if the command cannot run.

Qwen called `talos.run_command` and Talos reported the real bounded command result.

GPT-OSS called `talos.list_dir` repeatedly and never called `talos.run_command`. Talos correctly blocked success prose with:

> `[Command not run: talos.run_command was required for this explicit command request.]`

That is safe containment, but it is not good enough product behavior for an explicit command-profile request.

## Evidence

Audit:

`local/manual-testing/llama-cpp-t61m-full-e2e-audit-20260507-141417/FINDINGS-LLAMA-CPP-T61M-FULL-E2E-AUDIT.md`

Transcript:

- Qwen success path: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` around line `15580`.
- GPT-OSS failure path: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around line `16207`.

Provider-body evidence:

- `PROMPT-DEBUG-LLAMA-CPP-GPT-OSS-20B/prompt-debug-20260507-142513.provider-body.json`
- Final command reprompt exposed broad verification tools: `talos.retrieve`, `talos.run_command`, `talos.list_dir`, `talos.read_file`, `talos.grep`.
- No `tool_choice` field was present on that final provider request.

## Scope

Make explicit command-profile verification turns a command-only action path.

In scope:

- Detect `explicit-command-verification-request` contracts.
- For those turns, expose only `talos.run_command` in the native/prompt tool surface.
- Force provider tool use when the backend supports required tool choice and `talos.run_command` is visible.
- Preserve deterministic failure-dominant output if the model still does not call `talos.run_command`.
- Preserve broader inspection tools for general verify/explain turns that are not explicit command-profile requests.

Out of scope:

- Raw shell support.
- New command profiles.
- Full provider redesign.
- Changing command approval semantics.

## Acceptance

- Unit tests prove `ToolSurfacePlanner` returns only `talos.run_command` for explicit command-profile requests.
- Unit tests prove `ProviderRequestControlPolicy` returns `ToolChoiceMode.REQUIRED` for explicit command-profile turns with `talos.run_command` visible.
- Existing tests still prove non-command verification can inspect workspace evidence.
- Full Gradle test/build checks pass.
- A focused Qwen/GPT-OSS command audit confirms the command request provider body is command-only and forced where supported.

## Completion Notes

Implemented:

- `ToolSurfacePlanner` now gives explicit command-profile requests a command-only native/prompt surface: `talos.run_command`.
- Ordinary verification requests such as "Verify that the Gradle build passes" keep the broader verification surface.
- `ProviderRequestControlPolicy` now marks explicit command-profile requests with `ToolChoiceMode.REQUIRED` when `talos.run_command` is visible.
- Deterministic command-not-run containment remains in `ExecutionOutcome`.

Verification:

- Red tests first:
  - `ToolSurfacePlannerTest*explicitApprovedCommandProfileRequestExposesOnlyRunCommand`
  - `ProviderRequestControlPolicyTest*explicitCommandProfileRequestRequiresRunCommandToolChoice`
- Targeted tests passed after implementation:
  - `ToolSurfacePlannerTest`
  - `ProviderRequestControlPolicyTest`
  - `TaskContractResolverTest`
  - `ExecutionOutcomeTest`
  - `CompatChatClientTest`
- Full verification passed:
  - `.\gradlew.bat test --no-daemon`
  - `.\gradlew.bat build --no-daemon`
  - `.\gradlew.bat installDist --no-daemon`

Focused audit:

- `local/manual-testing/llama-cpp-t186-command-focused-audit-20260507-144029/FINDINGS-T186-COMMAND-FOCUSED-AUDIT.md`
- Qwen and GPT-OSS both received only `talos.run_command`, both called it once, and both reported the real bounded command failure.

Follow-up:

- `/prompt-debug last` captures the post-tool answer request, not the initial pre-tool command request. That means the saved provider body does not prove initial forced `tool_choice`; it proves command-only final surface. Track this prompt-debug observability issue separately.
