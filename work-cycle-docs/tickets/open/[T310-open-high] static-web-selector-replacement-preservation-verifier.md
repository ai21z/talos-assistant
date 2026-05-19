# T310 - Static Web Selector Replacement Preservation Verifier

Status: fixed in working tree / pending full gate
Severity: high
Release gate: yes for developer/code beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The Qwen 22-case synchronized approval live audit exposed a static-verification false success. Qwen rewrote `script.js` so `.missing-button` became `.cta-button`, but it also corrupted the existing result assignment from `textContent = 'Clicked'` to `textC;`. Talos still reported `Static web coherence checks passed`.

That is not acceptable. For a literal selector replacement request, static verification must prove that the requested replacement happened without silently damaging unrelated file content when same-turn read evidence exists.

## Evidence from current code

- `StaticTaskVerifier` checked HTML/CSS/JS selector coherence and accepted the mutated file because selectors linked correctly.
- `TaskExpectationResolver` did not derive a replacement expectation from the live wording `changing .missing-button to .cta-button`.
- Existing replacement verification only enforced preservation when the user explicitly said `preserve the rest`.
- `ToolCallExecutionStage` already records `FULL_WRITE_REPLACEMENT` evidence for `talos.write_file` when the target had complete same-turn `read_file` evidence, but the verifier did not use it for this live selector wording.

## Evidence from tests/audits

- Failure root:
  - `local/manual-testing/synchronized-approval-live-qwen-20260519-22case-r1/SYNCHRONIZED-APPROVAL-AUDIT-FAILED.md`
- Failure scenario:
  - `local/manual-testing/synchronized-approval-live-qwen-20260519-22case-r1/static-web-selector-script-only-verified/`
- Observed corrupted file:
  - `script.js` contained `document.querySelector('#result').textC;`
- Audit transcript still recorded:
  - `verificationStatus="PASSED"`
  - `verificationSummary="Static web coherence checks passed for 1 mutated target(s)."`
- Regression tests added:
  - `TaskExpectationResolverTest.extractsChangingLiteralToLiteralReplacementExpectationForExpectedTarget`
  - `StaticTaskVerifierTest.staticWebSelectorReplacementFailsWhenFullWriteCorruptsReadbackBody`

## User impact

Talos could claim a static-web fix was verified even though it broke existing JavaScript behavior. That is a false-success failure, not merely a weak model output.

## Product risk

High. Static web repair is part of the developer beta capability surface. A verifier that accepts syntactically plausible but behavior-breaking rewrites undermines Talos's evidence-driven product claim.

## Runtime boundary affected

Task expectation extraction, write-file mutation evidence, static web verification, final answer truthfulness, and live audit classification.

## Non-goals

- Do not add browser automation or JavaScript execution as the immediate fix.
- Do not auto-repair corrupted JavaScript after the verifier catches it.
- Do not weaken selector-target checks.

## Required behavior

For single-target selector replacement wording such as `changing .missing-button to .cta-button`, Talos must derive a replacement expectation and require preservation evidence. If `talos.write_file` is used, complete same-turn read evidence must prove the new full content equals the previous content with only the requested selector replacement applied.

## Proposed implementation

- Extend `TaskExpectationResolver` to parse selector-change wording into `ReplacementExpectation`.
- Mark this expectation as preserve-rest because changing one selector literal is a replacement, not an arbitrary rewrite.
- Reuse existing `FULL_WRITE_REPLACEMENT` evidence in `StaticTaskVerifier`.
- Keep existing static web selector coherence checks as a separate layer.

## Tests

- `TaskExpectationResolverTest.extractsChangingLiteralToLiteralReplacementExpectationForExpectedTarget`
- `StaticTaskVerifierTest.staticWebSelectorReplacementFailsWhenFullWriteCorruptsReadbackBody`
- Existing `StaticTaskVerifierTest` and synchronized approval e2e tests.

## Acceptance criteria

- The r1 corrupted `textC;` shape fails static verification.
- Correct exact-edit selector replacement still passes.
- Scripted synchronized approval audit passes.
- GPT-OSS and Qwen 22-case synchronized approval live audits pass the static-web selector scenario.
- Targeted runtime artifact scans pass on the live roots.

## Remaining blockers

- Full `clean check e2eTest` still needs to be rerun after the complete blocker batch.
- Full prompt-bank audit remains broader than this synchronized approval slice.

## Open questions

- Should all explicit `replace X with Y` expectations default to preserve-rest, or only selector-change/semantic replacement prompts with same-turn read evidence?
- Should static web verification include a lightweight JavaScript parser or syntax check in a later slice?

## Related files

- `src/main/java/dev/talos/runtime/expectation/TaskExpectationResolver.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/test/java/dev/talos/runtime/expectation/TaskExpectationResolverTest.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditMain.java`
