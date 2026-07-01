# [T71-done-medium] Exact Literal Verifier For Arbitrary Text Targets

Status: done
Priority: medium
Date: 2026-05-01
Completed: 2026-05-01

## Evidence Summary

- Source: T67 manual audit
- Summary:
  `local/manual-testing/t67-audit-20260501-143927/summary.md`
- Recovered session:
  `%USERPROFILE%/.talos/sessions/8d5e5c90b2f8140e09e5d7247d210c1cc1718331.turns.jsonl`

Observed behavior:

- Turns 17 and 18 wrote `README.md` with an exact two-line literal request:
  `first line T67 exact README; second line Line two; no other characters.`
- Traces:
  - `trc-78b58bc1-a072-4fcc-8a91-7e213d6fdc3c`
  - `trc-b51ba1d7-7c53-4b89-a588-e051aa7e83fa`
- Final file content was correct:

```text
T67 exact README
Line two
```

- User-visible verification was only readback:
  `No task-specific verifier was applicable ... Target/readback checks passed`.
- In the same audit, turn 20 (`trc-24c40332-bf10-4442-b552-0f0e55066c71`) for
  `Overwrite index.html with exactly AFTER` did trigger:
  `Static verification: passed - Exact content verification passed.`

## Classification

Primary taxonomy bucket: `VERIFICATION`

Secondary buckets:

- `LITERAL_INTENT`
- `OUTPUT_TRUTH`
- `MODEL_COMPETENCE`

Blocker level: medium follow-up

Why this level:

The file content was correct and readback truth was explicit, so this is not a
release-blocking false-success issue. But exact literal requests should receive
the same exact-content verification regardless of whether the target is
`index.html`, `README.md`, or another text file.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Special-case README.md.
```

Architectural hypothesis:

```text
Exact literal intent should produce a target-agnostic exact-content verifier
profile. File extension can affect additional validators, but exact requested
content should be checked for any text target.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/`
- `src/test/java/dev/talos/runtime/verification/`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Apply exact-content verification to arbitrary text-file targets when the user
asks for exact literal content.

## Non-Goals

- No binary file literal verifier.
- No multiline paste transport change; T66 already handles the current prompt
  discipline.
- No browser or shell execution.
- No weakening checkpoint/readback verification.

## Acceptance Criteria

- Exact literal requests for `README.md` select exact-content verification.
- Exact literal requests for generic `.txt`, `.md`, `.html`, `.css`, `.js`, and
  extensionless text files share the same core exact verifier.
- Exact-content mismatch produces `FAILED`/not verified outcome.
- Exact-content match produces explicit `Exact content verification passed`.
- Existing `index.html` exact literal behavior remains passing.

## Tests / Evidence

Required deterministic regression:

- Static verifier test: exact README content passes only when content matches.
- Static verifier test: exact README mismatch fails with precise reason.
- Executor/TalosBench case for exact README write after approval.
- Existing `literal-exact-write` case remains passing.

Suggested commands:

```powershell
.\gradlew.bat test --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

Executed evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
.\gradlew.bat test e2eTest --rerun-tasks --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
git diff --check
```

Resolution:

- Added deterministic exact-literal expectation parsing for explicit
  two-line full-file wording:
  `complete file must contain exactly two lines: first line X; second line Y; no other characters`.
- Kept exact-content verification target-agnostic by feeding the existing
  `LiteralContentExpectation` verifier instead of special-casing README.
- Added contextual extensionless text target resolution for common text files
  such as `README`, without treating the same words inside literal content as
  extra read/mutation targets.
- Added static verifier pass/fail regressions for exact README content and a
  TalosBench approved-write case that requires exact-content verification.

## Known Risks

- Exact literal parsing must stay conservative. Do not infer exact content from
  vague prose.
