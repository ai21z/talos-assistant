# T123 - Read-Only Evidence Sufficiency For Static Workspace Diagnosis

Severity: high
Status: done

## Problem

The T61-D managed llama.cpp audit showed that a read-only diagnostic turn can be marked complete after shallow evidence.

Qwen listed files, then answered that it needed to inspect `index.html`, `script.js`, and `styles.css` next. Talos classified the turn as `READ_ONLY_ANSWERED` even though the user asked whether the current static page button could work in a browser.

The current evidence rule is too coarse: "some read-only evidence was gathered" is not sufficient for all read-only tasks.

## Evidence

- `local/manual-testing/llama-cpp-t61d-full-audit-20260504-070432/FINDINGS-LLAMA-CPP-T61D-FULL-AUDIT.md`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` around the static page review turn.
- Architecture spec: `docs/superpowers/specs/2026-05-04-talos-capability-spine-workspace-architecture-design.md`

## Scope

- Add capability-specific evidence sufficiency for static web or obvious workspace diagnosis.
- A `list_dir` call alone should not satisfy a static web diagnosis when primary files such as `index.html` exist.
- If the assistant says it still needs to inspect after insufficient evidence, Talos should return evidence-incomplete or perform one bounded evidence retry.

## Acceptance

- A scripted Qwen-shaped case with `list_dir` then "I need to inspect" does not become `READ_ONLY_ANSWERED`.
- Static web diagnosis reads `index.html` at minimum when it exists.
- If linked JS/CSS files are necessary to answer the prompt, evidence policy either requires those reads or marks the answer incomplete.
- Existing names-only/list-only prompts remain list-only and do not read file contents.
- Final output is runtime-owned when evidence is incomplete.

## Non-Goals

- No new filesystem tools.
- No broad project-map feature.
- No command execution.

## Verification

- Add focused unit tests for evidence sufficiency.
- Add or update scripted/e2e coverage for static web diagnosis.
- Run targeted tests and `.\gradlew.bat --no-daemon build installDist`.
