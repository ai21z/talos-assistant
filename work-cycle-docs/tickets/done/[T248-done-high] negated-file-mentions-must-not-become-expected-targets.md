# [done] T248: Negated File Mentions Must Not Become Expected Targets

Date: 2026-05-11
Priority: high
Status: done

## Why This Ticket Exists

The broader T245-T247 audit found a deterministic contract bug. The user asked:

```text
create a bmi calculator web page using exactly index.html, styles.css, scripts.js. do not use script.js.
```

Talos injected:

```text
[ExpectedTargets]
requiredTargets: index.html, styles.css, scripts.js, script.js
```

That is wrong. `script.js` was mentioned only as a prohibited target, but Talos required it anyway.

## Evidence

- `local/manual-testing/t245-t247-broader-audit-20260511-211949/TEST-OUTPUT-QWEN-14B.txt:8156`
- `local/manual-testing/t245-t247-broader-audit-20260511-211949/TEST-OUTPUT-QWEN-14B.txt:8240`
- `local/manual-testing/t245-t247-broader-audit-20260511-211949/TEST-OUTPUT-QWEN-14B.txt:8296`
- `local/manual-testing/t245-t247-broader-audit-20260511-211949/TEST-OUTPUT-QWEN-14B.txt:8319`
- GPT-OSS showed the same expected-target shape in the BMI static-web turn.

Code observations:

- `TaskContractResolver.extractExpectedTargets(...)` extracts every filename-like mention.
- `TaskContractResolver.extractForbiddenTargets(...)` exists.
- `NEGATED_TARGET_SPAN` covers verbs like `change`, `edit`, `modify`, `write`, `create`, `save`, `apply`, `touch`, and `mutate`.
- It does not cover `do not use script.js`, so `script.js` remains in expected targets.

## Scope

In scope:

- Extend negated target extraction to cover user prohibitions such as:
  - `do not use script.js`
  - `don't use script.js`
  - `dont use script.js`
  - `avoid script.js`
  - `leave script.js alone`
  - `do not touch script.js`
  - `do not modify script.js`
- Ensure prohibited file mentions are removed from expected mutation targets.
- Ensure prompt-debug expected-target validation reflects the corrected set.
- Ensure expected-target progress reprompts do not demand negated targets.
- Ensure static verification does not fail solely because a negated target was not mutated.
- If a negated target is mutated, report it as a warning or failure according to current policy.

Out of scope:

- Broad natural-language target parsing rewrites.
- New planner architecture.
- Changing static-web verifier product expectations beyond correcting target contracts.

## Acceptance Criteria

- `TaskContractResolver.fromUserRequest("create ... index.html, styles.css, scripts.js. do not use script.js")` returns expected targets exactly `index.html`, `styles.css`, `scripts.js`.
- The corresponding forbidden targets include `script.js`.
- Current-turn capability frame and mutation retry frame do not include `script.js` as a required target.
- Tool-loop expected-target progress does not raise a pending obligation for `script.js`.
- Static verifier does not report `script.js: expected target was not successfully mutated` for that prompt.
- Tests cover at least:
  - `do not use script.js`
  - `don't use script.js`
  - `avoid script.js`
  - `leave script.js alone`
  - `do not create scripts.js` while requiring `script.js`
- Existing tests for positive `script.js` versus `scripts.js` distinction still pass.

## Likely Files

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`

## Verification Plan

- Add focused resolver tests first.
- Add a tool-loop transition test proving no pending obligation is raised for the negated file.
- Add or update static verifier coverage for `scripts.js` requested while `script.js` is explicitly forbidden.
- Run targeted tests.
- Run `.\gradlew test --no-daemon`.
- Rebuild/install Talos and run a focused two-model prompt probe for the BMI `scripts.js` case.

## Done Notes

- Extended forbidden target extraction for `do not use`, `avoid`, `leave ... alone`, and existing mutation negation variants.
- Added focused coverage in task-contract resolution, current-turn prompt framing, static verification, and tool-loop pending-obligation behavior.
- Verified targeted tests, full `.\gradlew test --no-daemon`, and `.\gradlew build --no-daemon`.
