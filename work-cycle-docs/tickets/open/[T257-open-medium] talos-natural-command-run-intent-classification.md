# T257 - Natural Command Run Intent Classification
Date: 2026-05-12
Status: Open
Priority: Medium

## Why This Ticket Exists

The model setup two-model audit asked:

```text
run the safe command check for this folder. if it can't run, say exactly that.
```

Observed:
- Both models received `WORKSPACE_EXPLAIN`.
- `talos.run_command` was not exposed.
- Qwen listed the workspace and inferred that no executable command was available.
- GPT-OSS used retrieval and answered that it could not run a command check.

Evidence:
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 8476-8561.
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 9983-10066.

## Problem

Command execution intent currently recognizes Gradle/profile-specific wording
better than natural user wording.

This specific audit prompt is somewhat vague, so the fix should be conservative.
Still, Talos should not let Qwen answer command capability from arbitrary fixture
text when the user asked to run a command check.

## Goal

Make command intent classification handle common user phrasing while preserving
the bounded command profile policy.

## Scope

In scope:
- Add focused tests for natural command prompts:
  - `run the tests here`
  - `run the gradle tests here`
  - `run the safe command check for this folder`
- Ensure `talos.run_command` is exposed only when a supported profile can be selected or a deterministic unsupported-command response is appropriate.
- Prevent answers that infer command capability from fixture config text.

Out of scope:
- Arbitrary shell execution.
- Expanding command profiles beyond existing approved profiles.

## Acceptance

- Explicit Gradle/test prompts expose `talos.run_command`.
- Unsupported vague command prompts get a deterministic unsupported-command answer or ask for a supported profile, not a workspace grep answer.
- Existing command boundary tests still pass.

## Required Verification

- Unit tests for command-intent classification and unsupported vague command routing.
- Integration/scripted REPL tests for explicit Gradle/test prompts and vague unsupported command prompts.
- Audit coverage can be focused; this does not need to block the first folder/summary/static-web fix batch.
