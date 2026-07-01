# [T74-done-high] Preamble-Tolerant Explicit Mutation Retry Classification

Status: done
Priority: high
Date: 2026-05-01
Closed: 2026-05-02

## Evidence Summary

- Source: T61-B milestone QA audit
- Transcript:
  `local/manual-workspaces/t61-b-milestone-qa-20260501-210434/TEST-OUTPUT-T61-B.txt`
- Findings:
  `local/manual-workspaces/t61-b-milestone-qa-20260501-210434/FINDINGS-T61-B.md`
- Analysis:
  `local/manual-testing/t61-b-milestone-qa-20260501-210434/analysis.md`
- Recovered session:
  `%USERPROFILE%/.talos/sessions/5e4d68c1ddb286b1946c8c01c4f4e21e02756ab2.turns.jsonl`

Observed behavior:

- Turn 30, trace `trc-26cc5901-8ffc-48cf-9634-727e9ffa2d1f`
  - Prompt:
    `This is a retry after the denied attempt. Edit README.md now using talos.write_file. The complete file must contain exactly two lines...`
  - Classified as `READ_ONLY_QA`.
  - No mutation tool was exposed/executed.
  - Similar retries at turns 31-33 stayed in the wrong mode.

Related prior tickets:

- Earlier denial/mutation tickets improved read-only denial dominance and exact
  literal verification.
- This ticket is a new classifier follow-up: the current failure is that an
  explicit retry mutation is not recognized after a natural-language preamble.

## Classification

Primary taxonomy bucket: `MUTATION_INTENT`

Secondary buckets:

- `RETRY_RECOVERY`
- `TASK_CONTRACT`
- `CONTROL_PLANE`

Blocker level: high before the next full T61-style audit

Why this level:

After a denied or blocked mutation, users naturally retry with explanatory
preambles. Talos must recognize explicit mutation intent without broadening into
unsafe mutation inference for status or review prompts.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Treat every prompt mentioning retry as mutation.
```

Architectural hypothesis:

```text
Mutation intent detection should tolerate short explanatory preambles when the
same current turn contains an explicit mutation verb, target filename, and
optionally a write-tool reference. The classifier should remain conservative
for questions, status checks, and review-only prompts.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/task/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/task/MutationIntentTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Classify explicit mutation retries with natural preambles as mutation tasks.

## Non-Goals

- No fallback mutation for ambiguous review/status prompts.
- No special casing only `README.md`.
- No bypass of approval policy.
- No change to exact literal verification itself.

## Acceptance Criteria

- The T61-B retry prompt classifies as `FILE_EDIT` or the existing mutation
  task type used for write-file edits.
- `Edit README.md now using talos.write_file` is recognized even when preceded
  by `This is a retry after the denied attempt.`
- Approval policy still controls whether the write executes.
- Read-only prompts such as `Review README.md`, `What happened after the denied
  attempt?`, and `Should I edit README.md?` remain non-mutating.
- Trace shows the mutation classification reason.

## Tests / Evidence

Required deterministic regressions:

- `MutationIntent` test for preamble plus explicit edit/file/tool phrase.
- `TaskContractResolver` test for the exact T61-B retry prompt.
- Negative tests for review/status/question prompts that mention retry or edit.
- TalosBench/manual case for denied write retry recovering into the approval
  path.

Suggested commands:

```powershell
.\gradlew.bat test --tests "*MutationIntent*" --tests "*TaskContractResolver*" --no-daemon
.\gradlew.bat test e2eTest --rerun-tasks --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
```

## Known Risks

- Over-broad matching could turn advisory or question prompts into writes.
- Exact-literal content may include words that look like mutation verbs; target
  extraction and literal expectation parsing must stay scoped.

## Closure Notes

- Added preamble-tolerant explicit mutation classification for current turns
  that contain a mutation verb plus a named file target.
- Preserved read-only classification for review, denied-attempt status,
  advisory edit, and instructional "how to edit" prompts.
- Added task contract and trace classification reason propagation so debug
  trace and `/last trace` can show why mutation mode was selected.
- Updated the T61 retry TalosBench manual case to use the preamble-first retry
  prompt and assert the classification reason.
- Verified with focused classifier/resolver tests, full unit/e2e tests,
  TalosBench validation, and TalosBench self-test.
