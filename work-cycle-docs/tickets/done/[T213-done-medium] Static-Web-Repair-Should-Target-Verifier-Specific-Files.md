# T213 - Static Web Repair Should Target Verifier-Specific Files

Status: done
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

## Completion Notes

Implemented in `RepairPolicy` by deriving verifier-specific structural repair targets when every static verifier problem maps to an implicated file class. CSS selector-source failures now narrow to stylesheet targets, JavaScript selector-source failures narrow to script targets, and HTML structural failures narrow to HTML targets. Mixed failures retain the broad structural target set.

Focused tests cover CSS-only narrowing and static repair obligation breach reporting for the narrowed target. A focused Qwen/GPT-OSS llama.cpp audit confirmed the CSS-only Qwen repair context named only `styles.css`, while the mixed GPT-OSS failure correctly retained `index.html`, `scripts.js`, and `styles.css`.

Audit findings:

`local/manual-testing/llama-cpp-t213-focused-re-audit-20260508-020613/FINDINGS-LLAMA-CPP-T213-FOCUSED-RE-AUDIT.md`

## Non-Goals

- Do not weaken static verification to accept incomplete pages.
- Do not remove the pending action-obligation gate.
- Do not attempt a full planner or multi-step repair architecture in this ticket.
