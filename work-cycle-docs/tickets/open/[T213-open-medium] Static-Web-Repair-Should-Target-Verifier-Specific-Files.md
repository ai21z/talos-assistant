# T213 - Static Web Repair Should Target Verifier-Specific Files

Status: open
Severity: medium

## Problem

The T61-R full llama.cpp audit shows GPT-OSS still failing the BMI/static web repair path. Talos now contains the failure correctly: static verification fails, pending repair obligations are raised, and success prose is suppressed. The remaining issue is repair effectiveness.

In the observed failure, static verification reported a CSS selector problem, but the repair continuation still required progress across broader static targets. GPT-OSS then mutated the wrong subset and Talos stopped deterministically.

## Evidence

Audit:

`local/manual-testing/llama-cpp-t61r-full-e2e-audit-20260508-001715/FINDINGS-LLAMA-CPP-T61R-FULL-E2E-AUDIT.md`

Relevant transcript lines:

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14231`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14267`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14955`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14980`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:15182`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:15206`

## Acceptance

- Static repair context derives narrower verifier-specific repair targets when the verifier can identify the implicated file(s).
- CSS selector mismatch repair should prefer the CSS target, or explicitly allow the smallest sufficient HTML/CSS target set.
- Existing failure-dominant behavior remains intact.
- Pending repair obligation trace events remain present and machine-readable.
- Qwen's passing static web path does not regress.
- GPT-OSS static web repair gets a focused clean re-audit after implementation.

## Non-Goals

- Do not weaken static verification to accept incomplete pages.
- Do not remove the pending action-obligation gate.
- Do not attempt a full planner or multi-step repair architecture in this ticket.
