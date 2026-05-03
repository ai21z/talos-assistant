# T106 - llama.cpp Focused Tool-Loop Audit And Ollama Retirement Decision

Status: Done
Priority: Medium
Branch: v0.9.0-beta-dev
Source: 2026-05-03 engine backend pivot
Design: `docs/superpowers/specs/2026-05-03-talos-engine-neutral-llama-cpp-design.md`

## Evidence Summary

The previous Qwen/GPT-OSS audits proved that prompt construction can be correct
while provider/tool-loop behavior still fails. The llama.cpp pivot must be
validated with the same discipline before any larger T61-style audit or default
engine decision.

Relevant current artifacts:

- `local/manual-testing/qwen-gptoss-full-audit-20260503-112017/FINDINGS-FULL-TWO-MODEL.md`
- `local/manual-testing/qwen-gptoss-full-audit-20260503-112017/PROMPT-CONSTRUCTION-ROOT-CAUSE-RESEARCH.md`
- `local/manual-testing/qwen-gptoss-full-audit-20260503-112017/TEST-OUTPUT-QWEN-14B.txt`
- `local/manual-testing/qwen-gptoss-full-audit-20260503-112017/TEST-OUTPUT-GPT-OSS-20B.txt`

## Classification

Primary taxonomy bucket: `ACTION_OBLIGATION`

Secondary buckets:

- `TOOL_SURFACE`
- `VERIFICATION`
- `OUTCOME_TRUTH`

Blocker level: required milestone validation before larger audit

## Architectural Hypothesis

The backend pivot should be judged by observable action-loop transitions and
provider-body JSON, not by final prose. Talos must prove that llama.cpp improves
or at least cleanly exposes the control surfaces needed by the runtime.

## Goal

Run a focused clean audit against the new llama.cpp path and decide whether
Ollama remains a legacy optional backend, stays as an alternate backend, or is
removed from the default install path.

## Scope

- Build/install Talos from `v0.9.0-beta-dev` after T102-T105 pass.
- Create a fresh manual-testing directory and fresh workspaces.
- Capture prompt debug and full provider-body JSON for key turns.
- Run focused prompt-construction probes:
  - expected targets;
  - exact complete-file writes;
  - script.js vs scripts.js;
  - wrong-target repair;
  - no-tool under pending obligation;
  - failure-dominant output.
- Record model/server setup:
  - llama.cpp version;
  - binary flavor;
  - model path/model id;
  - server flags;
  - chat template/tool settings.
- Produce findings comparing llama.cpp behavior against the prior Ollama
  Qwen/GPT-OSS findings.

## Non-Goals

- No full T61-style audit in this ticket.
- No broad model bakeoff.
- No patching prompt wording during the audit.
- No hiding provider-body failures behind final-answer prose.

## Acceptance Criteria

- Audit artifacts include prompts, test output, runner logs, provider-body JSON
  or trace references, and findings.
- Findings distinguish Talos runtime bug, provider limitation, model weakness,
  and setup/config issue.
- Provider-body capture proves whether `tool_choice` and/or `response_format`
  fields were sent on enforcement turns.
- Decision section states one of:
  - llama.cpp is ready to become default;
  - llama.cpp needs specific blocker tickets first;
  - Ollama must remain default temporarily;
  - Ollama can become legacy optional.
- No larger T61-style audit starts before this focused audit is reviewed.

## Suggested Verification

```powershell
./gradlew.bat clean installDist --no-daemon
```

Manual audit command sequence should be documented in the audit directory before
execution.

## Known Risks

- llama.cpp tool behavior depends on model and chat template. A failed audit
  must classify whether the fault is Talos serialization, server flags, model
  template, or model behavior.
- A single model pass is not enough to declare all llama.cpp setups safe.

## Known Follow-Ups

- Larger T61-style audit only after focused llama.cpp audit review.
- Possible future ticket for Talos-managed model download/checksum/profile
  registry.
