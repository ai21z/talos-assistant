# T198 - Static-Web-Diagnostic Truthfulness From Read Evidence

Status: open

Severity: high

Source audit: `local/manual-testing/llama-cpp-t61p-full-e2e-audit-20260507-180044/FINDINGS-LLAMA-CPP-T61P-FULL-E2E-AUDIT.md`

## Problem

Read-only static web diagnostics can produce an overconfident runtime-owned "no obvious problems" answer even when the read evidence shows the page cannot work.

This is not just model hallucination. The traces show `WEB_DIAGNOSTIC_GROUNDED_OVERRIDE`, meaning Talos replaced the assistant response with runtime-owned static diagnostics. The runtime-owned answer must be more truthful than model prose, not equally wrong.

## Evidence

Both models produced:

- `[Used 2 tool(s): talos.read_file | 2 iteration(s)]`
- `I inspected the primary web files`
- `Static web diagnostics did not find obvious HTML/CSS/JavaScript linkage problems.`
- `No files were changed.`

Evidence:

- Qwen output: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:8639-8649`
- GPT-OSS output: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:8597-8607`

But the read evidence for that same turn showed:

- `index.html` had no button.
- `index.html` did not link `script.js`.
- `script.js` queried `.cta-button`.
- `script.js` contained `result.textC;`, not a visible text assignment.

Evidence:

- Qwen read evidence: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9401-9424`
- GPT-OSS read evidence: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:9299-9326`

Trace evidence:

- Qwen trace warning: `SESSION-ARTIFACTS-LLAMA-CPP-QWEN-14B/traces/32b7f8f0c3f5218b08518350d7bce9b8449d5ce3/000018-trc-d381e2a0-dfc4-47a4-b151-ae2152a24aff.json:227-230`
- GPT-OSS trace warning: `SESSION-ARTIFACTS-LLAMA-CPP-GPT-OSS-20B/traces/cc93a665a4ac0d20cf3e9e39fa4e61d01922ff6f/000018-trc-a066d3b5-2de3-4aa5-b493-7dfd5660cba5.json:227-230`

Relevant code:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:3508-3521`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java:911-935`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java:997-1034`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java:1331-1340`

## Scope

- Make read-only static web diagnostics truthful from the evidence Talos actually read and/or the deterministic workspace snapshot it inspects.
- Report missing script linkage when HTML does not link the script file that contains relevant behavior.
- Report missing button/selectors when JavaScript expects `.cta-button` but the HTML has no matching button.
- Report broken result-text behavior for cases such as `result.textC;`.
- Do not output "Static web diagnostics did not find obvious..." when read evidence or deterministic snapshot evidence shows blockers.
- Do not claim CSS/JS files were inspected unless they were actually read by the tool loop or deterministically inspected by the runtime-owned diagnostic path.

## Non-Goals

- No browser automation.
- No full JavaScript execution engine.
- No broad redesign of static verification.
- No mutation behavior change.

## Acceptance Criteria

- Add tests where `index.html` has no `<button>` and no `<script src="script.js">`, while `script.js` queries `.cta-button` and contains `result.textC;`.
- The diagnostic output reports concrete blockers and does not contain "did not find obvious" for that case.
- Add tests for script-link mismatch and missing selector behavior.
- Add tests or assertions proving the rendered inspected-file list matches the evidence source.
- Existing static web coherence tests still pass.
- Existing T196 duplicate-summary tests still pass.
- `.\gradlew.bat test --no-daemon` passes.
- `.\gradlew.bat build installDist --no-daemon` passes.
- A focused two-model llama.cpp re-audit of the static web review prompt shows truthful diagnostics for the broken fixture.
