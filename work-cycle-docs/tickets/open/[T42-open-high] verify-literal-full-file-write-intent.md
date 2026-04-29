# [T42-open-high] Ticket: Verify Literal Full-File Write Intent
Date: 2026-04-29
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/06-bounded-repair-controller.md`
- `work-cycle-docs/tickets/done/[T40-done-high] mutation-request-with-format-negation-misclassified-read-only.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Why This Ticket Exists

T41 manual live-prompt testing showed Talos correctly classified exact
full-file overwrite prompts as mutation-capable, exposed write tools, required
approval, and created checkpoints. However, qwen wrote different content than
the user requested, and Talos only reported file write/readback success.

Observed prompts:

```text
Overwrite index.html with exactly AFTER. Use talos.write_file.
```

```text
Use talos.write_file to overwrite index.html. Set the content argument to the
exact five letters AFTER. Do not use angle brackets. Do not use placeholders.
The entire file should be AFTER.
```

In both cases the final `index.html` was an HTML page, not the literal
`AFTER`.

## Problem

Readback verification proves the tool wrote the model-provided payload, but it
does not prove the payload matches clear literal-content constraints in the
user request.

## Goal

For narrow literal full-file write requests, Talos should statically verify
that the final file content matches the requested literal content or report the
task as incomplete.

## Scope

In scope:
- Detect clear, narrow literal full-file overwrite constraints.
- Verify final file content against the requested literal content.
- Keep this deterministic and bounded.
- Preserve approval and checkpoint behavior.

Out of scope:
- General natural-language semantic diff verification.
- Browser execution.
- LLM-based verifier.

## Proposed Work

- Add a narrow literal-content extraction policy for patterns such as:
  - `with exactly AFTER`
  - `content argument to the exact five letters AFTER`
  - `The entire file should be AFTER`
- Attach the literal expectation to task verification when a target file is
  explicitly named.
- Fail or downgrade the outcome when the target file does not exactly match.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for literal-content extraction.
- Static verifier tests for matching and mismatching exact content.
- E2E scenario reproducing the T41 prompt shape.
- Manual installed Talos check with qwen if feasible.

## Acceptance Criteria

- Exact full-file overwrite prompts remain mutation-capable.
- If the file content is exactly the requested literal, verification passes.
- If the model writes different content, Talos does not imply the task is done.
- Final answer distinguishes write/readback from requested-content match.
- Existing readback-only wording remains truthful for non-literal tasks.
