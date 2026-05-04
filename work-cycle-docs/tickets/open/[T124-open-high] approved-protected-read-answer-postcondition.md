# T124 - Approved Protected Read Answer Postcondition

Severity: high
Status: open

## Problem

The T61-D managed llama.cpp audit showed that GPT-OSS can successfully read approved protected content, then refuse to answer with generic safety prose. Talos classified the turn as `READ_ONLY_ANSWERED` because the tool call succeeded and the model produced text.

That is not a correct completed answer. If the user grants approval and the protected read succeeds, the final response must either answer the approved request or provide a deterministic runtime-owned policy explanation.

## Evidence

- `local/manual-testing/llama-cpp-t61d-full-audit-20260504-070432/FINDINGS-LLAMA-CPP-T61D-FULL-AUDIT.md`
- GPT-OSS approved `.env` read in `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around the protected read turn.
- Qwen approved `.env` read answered correctly in the same audit.

## Scope

- Add a protected-read postcondition after successful approval and successful `read_file`.
- Generic model refusal after successful approved evidence should not be accepted as a completed answer.
- Runtime should render approved content when policy allows, or a deterministic policy-owned explanation if it cannot.

## Acceptance

- A scripted GPT-OSS-shaped case with successful `.env` read followed by "I'm sorry, but I can't provide that" is not `READ_ONLY_ANSWERED`.
- Denied protected read remains blocked and shows no content.
- Approved protected read answer remains local-only, traceable, and dominated by runtime policy.
- Prompt/debug trace records that the protected-read postcondition was checked.

## Non-Goals

- No weakening of protected-read approval.
- No automatic protected read without approval.
- No prompt wording-only fix.

## Verification

- Add focused tests for denied and approved protected reads.
- Add final-output assertions for refusal suppression/replacement.
- Run targeted tests and `.\gradlew.bat --no-daemon build installDist`.
