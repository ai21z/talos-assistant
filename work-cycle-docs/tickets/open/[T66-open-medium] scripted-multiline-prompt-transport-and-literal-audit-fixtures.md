# [T66-open-medium] Scripted Multiline Prompt Transport And Literal Audit Fixtures

Status: open
Priority: medium
Date: 2026-05-01

## Evidence Summary

- Source: T61 manual audit
- Transcript: `local/manual-workspaces/t61-audit-20260501-110306/TEST-OUTPUT-T61.txt`
- Related completed tickets:
  - `work-cycle-docs/tickets/done/[T42-done-high] verify-literal-full-file-write-intent.md`
  - `work-cycle-docs/tickets/done/[T55-done-high] current-turn-plan-immutable-turn-source-of-truth.md`
  - `work-cycle-docs/tickets/done/[T61-done-high] talosbench-t54-regression-pack.md`
  - `work-cycle-docs/tickets/done/talos-scripted-repl-stdin-approval-alignment.md`

Observed behavior:

- The intended exact README write prompt was entered as:

  ```text
  Replace README.md exactly with the text below and no extra prose:

  T61 exact README
  Line two
  ```

- The line-oriented REPL treated this as multiple turns:
  - turn 16: `Replace README.md exactly...`
  - turn 17: `T61 exact README`
  - turn 18: `Line two`
- The first turn attempted a write and was denied because no approval was
  supplied for that exact prompt.
- The later literal lines became independent `READ_ONLY_QA` prompts.
- Therefore the manual audit did not produce valid evidence for exact literal
  README write after retry.

Important line references:

- Multiline prompt split and approval denial:
  `TEST-OUTPUT-T61.txt:1371-1421`
- Literal payload lines handled as separate prompts:
  `TEST-OUTPUT-T61.txt:1422-1494`
- Retry turn no longer has the original literal payload and remains read-only:
  `TEST-OUTPUT-T61.txt:1549-1633`

## Classification

Primary taxonomy bucket: `EVALUATION_HARNESS`

Secondary buckets:

- `CLI_UX`
- `VERIFICATION`
- `LITERAL_INTENT`

Blocker level: medium release-gate support

Why this level:

This is not proof that exact literal write verification is broken. It is proof
that the current manual/scripted audit path can fail to deliver a multiline
logical prompt as one user turn. That can create false failures or hide real
literal-write regressions.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell auditors to paste more carefully.
```

Architectural hypothesis:

```text
TalosBench and manual audit workflows need a deterministic way to submit a
multiline logical prompt as one turn, or the literal-write release gates must
use single-line/escaped fixtures that the current REPL can transport reliably.
```

Likely code/document areas:

- `tools/manual-eval/run-talosbench.ps1`
- `tools/manual-eval/talosbench-cases.json`
- `tools/manual-eval/README.md`
- `src/main/java/dev/talos/cli/repl/`
- `src/main/java/dev/talos/cli/launcher/`
- `src/test/java/dev/talos/cli/`

## Goal

Make exact literal/multiline prompt audits reliable and reproducible.

## Non-Goals

- No change to literal-content verification semantics.
- No weakening approval prompts.
- No full TUI/editor mode unless a later UX ticket chooses that.
- No large parser rewrite.

## Acceptance Criteria

- TalosBench can represent and execute a multiline logical prompt as one turn,
  or the T61 literal README case is rewritten to avoid multiline transport
  ambiguity.
- The runner has a self-test or fixture test proving the prompt transport used
  by the literal case does not split the payload into separate user turns.
- Manual audit docs explain the supported way to enter multiline literal
  content.
- The exact README write after retry gate can be rerun and produces a valid
  `/last trace` for the intended logical prompt.
- Existing single-line TalosBench cases continue to run unchanged.

## Tests / Evidence

Required deterministic regression:

- Runner self-test for the chosen transport format.
- If REPL support is added, CLI/repl test proving a multiline logical prompt
  becomes one turn.
- TalosBench validate-only still passes.
- Manual rerun of exact README write after retry with a valid single-turn
  prompt.

Suggested commands:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
.\gradlew.bat test --no-daemon
```

## Known Risks

- A broad multiline REPL mode can complicate normal interactive use. Prefer the
  smallest deterministic transport that makes audits reliable.
