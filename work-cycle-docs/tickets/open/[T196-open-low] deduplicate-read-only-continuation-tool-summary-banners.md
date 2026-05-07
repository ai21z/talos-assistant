# T196 - Deduplicate Read-Only Continuation Tool Summary Banners

Status: open
Severity: low

## Evidence

Source audit:

- `local/manual-testing/llama-cpp-t61o-full-e2e-audit-20260507-162435/FINDINGS-LLAMA-CPP-T61O-FULL-E2E-AUDIT.md`

Concrete evidence:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:25345-25358`

## Problem

When read-only static web diagnostics use a continuation read, the visible output can show two separate `[Used 1 tool(s)]` banners before the final runtime-owned answer.

The underlying answer is grounded and correct, but the UI exposes continuation-loop mechanics.

## Scope

In scope:

- Collapse continuation read summaries into one concise tool summary for the visible answer.
- Preserve trace detail for each actual tool call.

Out of scope:

- Changing read-only evidence policy.
- Changing static web diagnostics behavior.

## Acceptance

- Focused test covers a read-only continuation with two reads and one visible combined summary.
- Trace still records both tool calls.
- Existing non-continuation output is unchanged.

