# T161 - Read-Only Review Proposals Must Not Present Unverified Commands Or Dependencies As Facts

Status: open

Severity: medium

## Problem

Read-only review/proposal responses can invent plausible setup commands, dependencies, and file meanings that were not observed in the target file or workspace evidence.

This is a model-behavior issue, but Talos should steer it better because users naturally treat review proposals as grounded.

## Evidence

T61-F managed llama.cpp response-quality review:

- `local/manual-testing/llama-cpp-t61f-full-audit-20260506-075339/MODEL-RESPONSE-QUALITY-REVIEW.md`

Qwen turn 10/11:

- Read `README.md`.
- Suggested `npm install`, `yarn install`, `npm start`, `yarn start`, and Node/npm/yarn dependencies with no evidence the fixture is a Node project.

GPT-OSS turn 10/11:

- More caveated than Qwen, but still suggested placeholder command/file meanings not grounded in README content.
- In turn 11, user said "I do not want the .env"; the response still suggested documenting `.env`.

Primary-source context:

- OWASP LLM09 identifies unsupported claims and hallucinated plausible content as misinformation risk.
- NIST AI RMF treats validity/reliability and accuracy as trustworthiness requirements.

## Scope

- Strengthen current-turn framing for read-only review/proposal tasks:
  - separate "observed from file" from "suggested if applicable";
  - do not state commands, dependencies, package managers, frameworks, scripts, licenses, or file meanings as facts unless observed in the workspace evidence;
  - use placeholders or say "if applicable" for unverified suggestions;
  - respect negated protected-path focus such as "I do not want the .env".
- Apply to proposal/review turns, not general creative writing.
- Preserve useful concise suggestions.

## Acceptance

- Add tests where README contains no package/build evidence and the model response tries to include npm/yarn/Gradle/Maven commands as facts; Talos prompt framing or output policy must prevent or flag the ungrounded wording.
- Add tests where unverified commands are allowed only as explicitly marked placeholders, not facts.
- Add tests where user says "I do not want .env, I want README.md" and the response does not introduce `.env` advice unless README itself mentions it.
- Existing read-only review and proposal tests still pass.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Non-Goals

- Do not build a general-purpose semantic truth verifier.
- Do not forbid suggestions.
- Do not require web access for local README reviews.
