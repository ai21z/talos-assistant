# T229 - Compact Mutation Continuation Static Web Coherence Guidance

Status: done
Severity: high
Source: T228 focused llama.cpp Qwen/GPT-OSS audit

## Problem

T228 fixed the compact mutation continuation transition for context-budget pressure, but the compact frame is thinner than the normal static web repair frame.

For static web creation or rewrite requests, the compact continuation carries the current request, expected targets, readback evidence, narrowed write/edit tools, and required tool choice. It does not carry the static web cross-file coherence checklist. In the focused audit, GPT-OSS used the compact continuation and wrote the expected files, but static verification failed because CSS referenced `.result` while the rewritten HTML did not define that class.

## Evidence

Audit:
`local/manual-testing/llama-cpp-t228-focused-audit-20260508-102946`

GPT-OSS:
- Repeat BMI create no longer failed on context budget; trace recorded `COMPACT_MUTATION_CONTINUATION`.
- Static verification still failed with `CSS references missing class selectors: .result`.
- Output: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around lines 14282-14364.
- Compact prompt: `PROMPT-DEBUG-LLAMA-CPP-GPT-OSS-20B/prompt-debug-20260508-104215-10.md`.
  - Lines 1-14: compact continuation, required tool choice, expected targets.
  - Lines 33-48: compact frame and target spelling warning.
  - No static web coherence checklist is present.
- Normal static repair prompt: `PROMPT-DEBUG-LLAMA-CPP-GPT-OSS-20B/prompt-debug-20260508-104224.md` lines 49-54 includes the cross-file checklist.

Relevant source:
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/capability/StaticWebCapabilityProfile.java`

## Scope

In scope:
- When compact mutation continuation targets include small static web files (`.html`, `.css`, `.js`), include the existing static web cross-file coherence checklist in the compact frame.
- Reuse `StaticWebCapabilityProfile.repairCoherenceGuidance` rather than duplicating checklist text.
- Keep compact continuation narrow: current request, expected targets, readback evidence, and required write/edit tools.
- Preserve T228 deterministic failure behavior when compact continuation returns no mutation.

Out of scope:
- No verifier changes.
- No new provider abstraction.
- No broader prompt wording rewrite.
- No change to normal static repair prompt.
- No full T61-style audit for this ticket alone.

## Acceptance

- Add a failing test showing the compact static web continuation prompt includes:
  - `Cross-file coherence checklist`
  - `HTML must link every CSS and JavaScript file being written`
  - `Every JavaScript ID or selector must exist in HTML`
  - `CSS selectors should correspond to classes or IDs in HTML`
- Add or preserve a non-static compact continuation test proving the checklist is not injected for a non-web exact/prose target.
- Existing compact mutation no-tool failure remains failure-dominant.
- Targeted ToolCallLoop tests pass.
- Full `gradlew test` and `gradlew build installDist` pass.
- `git diff --check` passes.

## Audit Follow-Up

After implementation and tests, run a focused compact-continuation audit before the next broad product audit. The audit should confirm the compact static web prompt now includes the coherence checklist and that GPT-OSS no longer fails the repeat BMI create for the missing `.result` selector shape, or else records a more specific remaining model/runtime failure.

## Verification

- Targeted ToolCallLoop compact-continuation tests passed on 2026-05-08.
- `.\gradlew.bat test --no-daemon` passed on 2026-05-08.
- `.\gradlew.bat build installDist --no-daemon` passed on 2026-05-08.
- `git diff --check` passed on 2026-05-08.
