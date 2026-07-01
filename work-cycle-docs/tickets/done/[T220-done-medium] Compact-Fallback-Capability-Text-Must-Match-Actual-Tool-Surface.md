# [T220-open-medium] Compact Fallback Capability Text Must Match Actual Tool Surface

Status: done
Priority: medium

## Evidence Summary

- Source: focused manual llama.cpp audit
- Date: 2026-05-08
- Talos version / commit: 1ad24cd T219 compact exact-write context fallback
- Model/backend: managed llama.cpp with qwen2.5-coder:14b and gpt-oss:20b
- Raw transcript paths:
  - `local/manual-testing/llama-cpp-t219-focused-exact-context-audit-20260508-050906/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
  - `local/manual-testing/llama-cpp-t219-focused-exact-context-audit-20260508-050906/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`
- Prompt debug paths:
  - `local/manual-testing/llama-cpp-t219-focused-exact-context-audit-20260508-050906/PROMPT-DEBUG-LLAMA-CPP-QWEN-14B/`
  - `local/manual-testing/llama-cpp-t219-focused-exact-context-audit-20260508-050906/PROMPT-DEBUG-LLAMA-CPP-GPT-OSS-20B/`
- Findings report:
  - `local/manual-testing/llama-cpp-t219-focused-exact-context-audit-20260508-050906/FINDINGS-LLAMA-CPP-T219-FOCUSED-EXACT-CONTEXT-AUDIT.md`

Expected behavior:

```text
When compact exact-write fallback narrows the backend tool surface to only
talos.write_file, every prompt/debug surface should describe only that available
tool. No text in the current-turn capability frame should claim talos.edit_file
is available when the backend did not receive that tool.
```

Observed behavior:

```text
Prompt debug correctly shows:
- Tools: talos.write_file
- visibleTools: talos.write_file

But the capability-frame body still says:
Available mutating tools: talos.write_file, talos.edit_file.
```

## Classification

Primary taxonomy bucket:

- `TOOL_SURFACE`

Secondary buckets:

- `CURRENT_TURN_FRAME`
- `PROMPT_DEBUG`

Blocker level:

- candidate follow-up

Why this level:

```text
The mismatch did not break the focused audit because both models called
talos.write_file, but it is a deterministic prompt/tool contract contradiction.
It undermines the purpose of compact fallback and should be removed before the
next broader product audit.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Change one sentence in the prompt.
```

Architectural hypothesis:

```text
CurrentTurnCapabilityFrame renders the canonical visible tool list from
CurrentTurnPlan.nativeTools(), but the MUTATING_TOOL_REQUIRED guidance contains
a hard-coded "Available mutating tools" sentence. That hard-coded sentence can
drift from narrowed runtime tool surfaces.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`

Why a one-off patch is insufficient:

```text
Tool availability should have one source of truth. The capability frame should
derive all availability text from the actual visible/native tools passed to the
backend, especially during compact fallback and repair turns that intentionally
narrow the surface.
```

## Goal

```text
For mutating obligations, rendered "available mutating tools" text must list
only actual visible mutating tools. If the backend receives only talos.write_file,
the prompt must not claim talos.edit_file is available.
```

## Non-Goals

- No new provider abstraction.
- No new task classification.
- No broad prompt rewrite.
- No change to normal full mutating turns that actually expose both write_file and edit_file.

## Acceptance

- Add a focused failing test proving compact exact-write fallback does not mention unavailable `talos.edit_file`.
- `CurrentTurnCapabilityFrame` derives mutating-tool availability text from the visible tool list.
- Normal mutating prompts that expose both `talos.write_file` and `talos.edit_file` still mention both.
- Focused tests pass.
- Full Gradle tests and build/install pass before closing.
