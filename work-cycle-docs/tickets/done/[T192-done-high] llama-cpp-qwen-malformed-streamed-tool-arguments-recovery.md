# T192 - llama.cpp Qwen Malformed Streamed Tool Arguments Recovery

Status: done
Severity: high

## Evidence

Source audit:

- `local/manual-testing/llama-cpp-t61o-full-e2e-audit-20260507-162435/FINDINGS-LLAMA-CPP-T61O-FULL-E2E-AUDIT.md`

Concrete evidence:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:17851-17918`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:19083-19147`
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/talos.log:2`
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/talos.log:3`

## Problem

Qwen on managed llama.cpp can return streamed tool-call argument chunks that Talos still cannot decode. The failure is safely contained as `BACKEND_MALFORMED_RESPONSE`, but it blocks a core multi-file create workflow.

## Scope

In scope:

- Inspect the OpenAI-compatible streaming decoder for unsupported streamed `tool_calls[].function.arguments` shapes.
- Add a bounded recovery path if code inspection shows it is safe.
- Prefer one non-streaming retry for required tool-call mutation turns after a malformed streamed tool-argument response.
- Preserve failure-dominant output if recovery also fails.
- Improve trace/debug evidence enough to identify the unsupported streamed shape without leaking workspace content.

Out of scope:

- Replacing llama.cpp.
- Broad provider abstraction.
- Unbounded retry loops.
- Treating backend/protocol failure as success.

## Acceptance

- Tests cover malformed streamed tool arguments on a required mutation turn.
- If a non-streaming retry succeeds, the tool call is executed normally and verified normally.
- If retry fails, final output remains failure-dominant and records a typed backend/protocol failure.
- Trace/debug records the streamed failure and the bounded recovery attempt.
- Existing GPT-OSS happy path is unchanged.
