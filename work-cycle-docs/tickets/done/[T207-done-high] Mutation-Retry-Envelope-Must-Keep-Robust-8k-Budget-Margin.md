# T207 - Mutation Retry Envelope Must Keep Robust 8k Budget Margin

Status: done
Severity: high

## Problem

T205 made the missing-mutation retry compact enough for the focused audit at that point, but the next focused T206 audit showed the retry envelope is still too close to the managed local 8192-token context limit.

Both model runs stopped before the retry could reach the backend:

- Qwen: estimated `5658` input tokens, budget `5635`, context window `8192`.
- GPT-OSS: estimated `5636` input tokens, budget `5635`, context window `8192`.

This is not a model-behavior failure. It is a runtime envelope sizing failure. The retry budget must have useful margin under the 8k local path, not merely pass one prompt shape by a few tokens.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t206-focused-re-audit-20260507-214500`

Observed output:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`: `[Action obligation failed: retry could not fit in the context budget.]`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`: `[Action obligation failed: retry could not fit in the context budget.]`

Code path:

- `AssistantTurnExecutor.mutationRequestRetryIfNeeded(...)`
- `AssistantTurnExecutor.compactMutationRetryMessages(...)`
- `AssistantTurnExecutor.compactMutationRetryFrame(...)`
- `AssistantTurnExecutor.mutationRetryInstruction(...)`
- `AssistantTurnExecutor.compactMutationRetryToolSpec(...)`
- `LlmClient.fitMessagesToContextBudget(...)`

## Scope

- Remove duplicated or non-essential retry payload from the backend retry request.
- Preserve the durable transcript trace on the main message history.
- Preserve:
  - current user request,
  - mutation/conditional review-fix obligation,
  - expected targets,
  - exact `script.js` vs `scripts.js` distinction,
  - narrowed retry tool surface,
  - deterministic failure when the model still emits no write/edit tool call.
- Add tests that account for retry tool-schema payload, not only message text.

## Non-Goals

- Do not change ordinary first-turn prompt construction.
- Do not raise the context window.
- Do not relax the local response reserve.
- Do not remove failure-dominant output.
- Do not change T206 conditional no-change wording logic.

## Acceptance

- A red/green test proves the retry request sent to the backend does not include redundant retry failure prose.
- A focused test covers conditional review/fix retry payload size including compact tool schemas.
- Existing T205 retry tests continue to prove old history and the full system prompt are excluded.
- Targeted tests pass.
- Full `test` and `build installDist` pass before closure.
- Re-run the T206 focused audit shape and confirm Qwen/GPT-OSS no longer fail with `retry could not fit in the context budget`.

## Resolution Notes

Implemented in `AssistantTurnExecutor`.

The bounded missing-mutation retry still records the runtime failure summary in durable conversation history, but no longer sends that redundant assistant failure-summary prose inside the compact backend retry request.

The retry-only mutation tool schemas were reduced to the required fields for `talos.write_file` and `talos.edit_file`, keeping the ordinary tool schemas unchanged.

Focused tests:

- Added a red/green assertion that conditional review/fix retry payloads exclude redundant failure-summary prose.
- Added a payload-size assertion that includes compact retry tool schemas.
- Existing T205 tests continue to prove old history and the full leading system prompt are excluded.

Focused re-audit:

`local/manual-testing/llama-cpp-t207-focused-re-audit-20260507-214216/FINDINGS-LLAMA-CPP-T207-FOCUSED-RE-AUDIT.md`

Result:

- Qwen and GPT-OSS completed with return code `0`.
- No `retry could not fit in the context budget` failure appeared.
- No `Action obligation failed` appeared.

Audit limitation:

The live path did not force the artificial missing-mutation retry branch this time because both models reached the runtime-owned conditional no-change path. The retry branch is accepted based on deterministic red/green tests with realistic compact mutation schemas plus the focused audit confirming the previous live budget failure no longer appears.
