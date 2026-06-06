# T701 - Static-Web Status Answers Use Last Verification State

Status: open
Severity: high

## Problem

In T698, Qwen answered a status-only prompt after failed static-web verification with:

```text
The static verification indicates that the required content and structure are present in the files.
```

That contradicted the latest verifier state. The previous static-web turn had failed, and the status-only turn did not run post-apply verification.

## Evidence

- Audit root:
  `local/TalosTestOUTPUT/test02-11-post-t697-t698-sync-audit-20260606-131440/`
- Qwen fresh transcript:
  - P1/P2 verification failed.
  - P4 prompt: `Is it verified now? What, if anything, is still unverified?`
  - P4 trace: `READ_ONLY_QA`, `Verification: NOT_RUN`, `Outcome: READ_ONLY_ANSWERED`.
  - P4 assistant preview overclaimed static verification success.
- Final Qwen workspace still had unresolved static-web concerns:
  - remote Tailwind CSS href not accepted as Tailwind runtime/build proof,
  - exact required phrase drift still flagged by content preservation.

## Architecture Metadata

- Capability ownership: status/read-only outcome rendering / verification-state memory.
- Operation type: read-only status/explanation turn after a prior mutation/verification turn.
- Risk: high. Users ask status prompts to decide whether to trust the result.
- Approval behavior: no mutation tools should be exposed.
- Protected path behavior: unchanged.
- Checkpoint behavior: unchanged.
- Evidence obligation: status answer must be grounded in latest available verification/trace state or state that no current verifier state is available.
- Verification profile: status turns do not run post-apply verification unless a dedicated verify-only path exists.
- Repair profile: none.
- Outcome/trace changes: status answer should surface previous failed/unverified state without pretending a new verification ran.
- Allowed refactor scope: outcome rendering, session turn lookup, status/read-only answer guards, trace rendering tests.

## Acceptance

- After a static-web turn with `Verification: FAILED`, a follow-up `Is it verified now?` must answer from the latest stored verifier state.
- It must not say static verification indicates success unless the latest verifier state actually passed.
- If no latest verifier state is available in the current process/session, it must say that explicitly and may inspect files, but must not infer verified status from file reads alone.
- Status-only prompts remain read-only and expose no mutation tools.
- Explanation-only prompts can cite the latest verifier problems and inspected files.

## Tests

- CLI/repl or outcome-rendering test: after a failed static-web turn, status-only follow-up renders the failed verifier summary.
- Read-only answer guard test: model-authored "static verification indicates success" is replaced or annotated when latest verification failed.
- Session/new-process test: if no prior verifier state is loaded, status answer says no loaded prior verification state is available instead of claiming success.
- Regression test for no mutation tools on status prompt remains green.

## Non-Goals

- Do not make every status prompt trigger a full verifier run.
- Do not load prior sessions implicitly.
- Do not add visual/render verification.
