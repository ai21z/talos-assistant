# T151 - Static Web Repair Recovers From Edit Failure And Loop-Limit Success

Status: open
Priority: high

## Evidence Summary

- Source: manual llama.cpp product workflow re-audit
- Date: 2026-05-05
- Talos version / commit: `53106ca`
- Model/backend: managed llama.cpp with `qwen2.5-coder:14b` and `gpt-oss:20b`
- Workspace fixture: `local/manual-workspaces/llama-cpp-product-workflow-reaudit-20260505-183450`
- Raw transcript path:
  - `local/manual-testing/llama-cpp-product-workflow-reaudit-20260505-183450/TEST-OUTPUT-LLAMA-CPP-PRODUCT-WORKFLOW-QWEN-14B.txt`
  - `local/manual-testing/llama-cpp-product-workflow-reaudit-20260505-183450/TEST-OUTPUT-LLAMA-CPP-PRODUCT-WORKFLOW-GPT-OSS-20B.txt`
- Findings report:
  - `local/manual-testing/llama-cpp-product-workflow-reaudit-20260505-183450/FINDINGS-LLAMA-CPP-PRODUCT-WORKFLOW-REAUDIT.md`
- Verification status: broad product workflow is not ready for larger T61-style audit.

Redacted prompt sequence:

```text
Fix the static web button fixture. The existing index.html loads script.js; the button with id run-button should set #result to Clicked. Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.
```

Expected behavior:

```text
Talos should repair the static web fixture by mutating the necessary target file(s),
verify the final HTML/CSS/JS coherence, and produce a clean verified result without
failure-policy stops or tool-call/iteration-limit warnings.
```

Observed behavior:

```text
Qwen repaired script.js and static verification passed, but the turn consumed 13
tools / 10 iterations and reported the tool-call limit.

GPT-OSS failed the same repair after repeated read/list cycles and invalid
edit_file arguments for script.js. The runtime truth check correctly reported no
file changes and the final workspace still had the broken .missing-button selector.
```

## Classification

Primary taxonomy bucket:

- `REPAIR_CONTROL`

Secondary buckets:

- `ACTION_OBLIGATION`
- `VERIFICATION`
- `OUTCOME_TRUTH`

Blocker level:

- release blocker for T61 readiness

Why this level:

```text
Static web repair is a normal developer-assistance workflow. One required audit
model fails it, and the other reaches the loop limit while passing it. That is
not stable enough to start a larger T61-style audit.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Make the static web prompt clearer.
```

Architectural hypothesis:

```text
The issue is in repair/tool-loop control. When edit_file fails with old_string
not found for a small static web target, the loop keeps depending on the model
to produce a better exact edit. For small text fixtures, Talos should recover
deterministically toward a complete write_file replacement after a fresh read.

Separately, after static web coherence is already satisfied, repeated duplicate
mutations should not leave the user with a verifier-passed result plus a
tool-call-limit warning.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/toolcall/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`

Why a one-off patch is insufficient:

```text
This same class can recur for any small static target where exact edit arguments
fail after the model has already inspected the file. The invariant belongs in
repair control and outcome handling, not just in the prompt wording.
```

## Goal

```text
Static web repair should either finish cleanly with verified final-state success
or fail deterministically with a precise repair failure. It should not fail only
because the model repeated invalid exact edits, and it should not report success
only after hitting the tool-call limit.
```

## Non-Goals

- No shell/browser unless the milestone explicitly includes it.
- No MCP or multi-agent behavior unless explicitly approved.
- No LLM classifier for safety-critical permission, privacy, mutation, or verification policy.
- No giant untyped phrase dump without an owner policy.
- No bypassing approval, permission, checkpoint, trace, or verification.
- No committing raw private transcripts.
- No broad rewrite of the tool-loop architecture.
- No change to protected read behavior.
- No new delete tool.

## Implementation Notes

```text
Prefer a narrow deterministic recovery:

1. Detect static web repair target(s) with small text content.
2. If edit_file fails with old_string not found after the file has been read,
   make the next repair attempt favor complete write_file replacement for the
   same target.
3. Preserve successful edit_file behavior.
4. If final static verification passes, avoid presenting that result together
   with an avoidable tool-call-limit warning caused by repeated duplicate writes.
5. If recovery still fails, keep failure-dominant output.
```

## Architecture Metadata

Capability:

- Static web repair

Operation(s):

- read
- edit
- write
- verify

Owning package/class:

- `dev.talos.runtime.toolcall`
- `dev.talos.runtime.repair`
- `dev.talos.runtime.verification`

New or changed tools:

- none expected

Risk, approval, and protected paths:

- Risk level: write
- Approval behavior: unchanged approval for write/edit calls
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: use fresh read evidence for rewrite recovery
- Verification profile: static web coherence
- Repair profile: static web repair

Outcome and trace:

- Outcome/truth warnings should remain runtime-owned.
- Trace should make edit-failure recovery visible enough to diagnose.

Refactor scope:

- Allowed: small helper extraction if needed to keep repair logic cohesive.
- Forbidden: broad AssistantTurnExecutor rewrite.

## Acceptance Criteria

- GPT-OSS-shaped failure is covered: invalid `edit_file` old_string after read should lead to a bounded write_file recovery path for the same static web target.
- Qwen-shaped repeated write behavior is covered: a static web repair that reaches verifier-passed final state should not surface an avoidable tool-call-limit success.
- Successful valid `edit_file` static web repair still works.
- Failed recovery remains failure-dominant and does not include success/manual-save prose.
- Changed-files summary remains runtime-owned and accurate.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: repair policy/tool-loop detects `old_string not found` for static web target and prefers complete rewrite recovery.
- Integration/executor test: static web fixture with broken `.missing-button` selector is recovered after an invalid first edit.
- Integration/executor test: repeated duplicate static web writes do not produce verifier-passed output plus avoidable tool-limit warning.
- Trace assertion: recovery event or repair framing identifies the target path and the reason for switching strategy.

Manual rerun:

- Prompt family: product workflow static web repair step.
- Workspace fixture: same product workflow fixture.
- Expected outcome:
  - Qwen and GPT-OSS both repair the button fixture cleanly, or fail with deterministic failure-dominant output.
  - No GPT-OSS failure-policy stop for repeated invalid exact edits.
  - No Qwen verifier-passed output with tool-call-limit warning.

Commands:

```powershell
./gradlew.bat test --no-daemon
```

Add broader commands if runtime code changes:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use focused tests first.
- Run a focused static web repair re-audit with both llama.cpp models before the broader product workflow rerun.
- Do not start T61 until this ticket is closed and the broader product workflow rerun is clean enough.

## Known Risks

- Complete-file rewrite recovery must be scoped to small text targets and current-turn static web repair, not generalized to all edit failures.
- Avoid hiding real tool-call-limit problems. The fix should prevent avoidable limit noise, not suppress meaningful failures.

## Known Follow-Ups

- If this still depends too much on model compliance, consider a richer repair-action controller for small static fixtures.
