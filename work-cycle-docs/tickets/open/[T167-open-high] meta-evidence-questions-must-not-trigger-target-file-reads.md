# T167 - Meta-Evidence Questions Must Not Trigger Target File Reads

Status: open

Severity: high

Source audit:
- `local/manual-testing/llama-cpp-t61g-big-audit-20260506-172941/FINDINGS-LLAMA-CPP-T61G-BIG-AUDIT.md`

## Problem

Questions about whether Talos previously read a file are meta-evidence/session
questions, not file-content questions. Talos currently treats a named file in
that prompt as a target that must be read.

In the T61-G audit, this prompt:

```text
Based only on verified evidence from this session, did you read notes.md? Answer yes or no and one sentence.
```

was classified as `READ_ONLY_QA` with `READ_TARGET_REQUIRED`. GPT-OSS read
`notes.md` during the turn, then answered "Yes". That answer became true only
because Talos caused the action the user was asking about.

Qwen hit a malformed backend response on the same forced-read shape.

## Evidence

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14329-14353`
  - prompt classified with `READ_TARGET_REQUIRED`
  - GPT-OSS reads `notes.md`
  - answer says it read `notes.md`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14368-14379`
  - trace confirms `talos.read_file -> notes.md [ok]`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:14099-14122`
  - same prompt classified as `READ_TARGET_REQUIRED`
  - Qwen fails with malformed engine response before tool completion

## Scope

In scope:
- Add or extend task classification for meta-evidence/session-history questions.
- Answer from runtime-owned turn trace/session data when the user asks whether
  Talos already read, wrote, inspected, changed, or used a file/tool.
- Do not read the named target unless the user explicitly asks for its current
  contents.
- Ensure prompt-debug/current-turn frame reflects session-trace evidence rather
  than `READ_TARGET_REQUIRED`.

Out of scope:
- Do not generalize into a full natural-language audit query engine.
- Do not change normal `Read README.md` behavior.
- Do not make `notes.md` specially protected.

## Acceptance

- `Did you read notes.md?` after no prior read answers `No` without reading
  `notes.md`.
- If Talos did previously read the file, the answer can say `Yes` from trace
  evidence without reading it again.
- The turn uses no file tools unless explicitly requested.
- Saved prompt-debug/provider-body artifacts do not acquire new private file
  contents from meta-evidence questions.
- `.\gradlew.bat --no-daemon check installDist` passes.
