# T237 - Summarize Source Into File Must Be Mutating With Evidence

Status: open
Priority: high

## Evidence Summary

Source audit:

- `local/manual-testing/user-perspective-broad-audit-20260511-080320/FINDINGS-USER-PERSPECTIVE-BROAD-AUDIT.md`
- Qwen transcript: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2698-2720`
- GPT-OSS transcript: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:3240-3330`

Observed behavior:

```text
User asked Talos to summarize long-notes.txt into docs/summary.md.
Talos classified the turn as READ_ONLY_QA and exposed read-only tools.
The source was inspected, but the runtime never transitioned to a write-capable
phase for docs/summary.md.
No summary file was created.
```

Expected behavior:

```text
"Summarize/read source into target file" is a mixed evidence + mutation task.
Talos should gather source evidence, then write the requested target file in
the same turn with normal approval and verification.
```

## Classification

Primary taxonomy bucket:

- `TASK_CONTRACT`

Secondary buckets:

- `CURRENT_TURN_FRAME`
- `ACTION_OBLIGATION`
- `EVIDENCE_OBLIGATION`

## Goal

Make source-to-target artifact requests first-class mutating tasks with
evidence gathering.

## Acceptance Criteria

- `Summarize long-notes.txt into docs/summary.md` derives:
  - source evidence target: `long-notes.txt`,
  - mutation target: `docs/summary.md`,
  - write-capable apply phase after evidence is gathered.
- The source file is read before writing unless already safely available in the
  current turn.
- Protected source files still require protected-read approval.
- Protected files not named by the user are not read.
- The target file is written after approval.
- Final output is readback/truthful and does not claim a summary was created if
  the write did not happen.

## Non-Goals

- No general planner.
- No multi-document summarization pipeline beyond explicit source-to-target
  requests.
- No protected-content leak into prompt-debug or trace output.

## Suggested Tests

- Contract resolver: `summarize A into B` is mutating, not `READ_ONLY_QA`.
- Executor: source read result is followed by visible `write_file`/`edit_file`
  tools for the target.
- E2E: `docs/summary.md` exists after approval and does not include protected
  marker content from unrelated protected files.
- Denied protected source read blocks without writing.
