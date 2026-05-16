# T281 - Private Mode User-Facing UX and Sensitive Folder Warning

Status: open
Severity: high / P0 for private-document beta
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Private mode must be visible and understandable to users. A config-only privacy setting is not enough for folders likely to contain tax, health, legal, family, finance, or admin paperwork.

## Evidence from current code

This pass adds `PrivacyCommand` and `SensitiveWorkspaceDetector`. `/privacy status`, `/privacy private on`, `/privacy private off`, and `/privacy help` exist, and startup can warn when shallow workspace metadata looks sensitive.

## Evidence from tests/audits

`PrivacyCommandTest` and `SensitiveWorkspaceDetectorTest` cover the minimal command and warning behavior. A later focused two-model beta-core capability audit ran as `capability-live-audit-20260516-195820`, including private search/status prompts.

## User impact

Users can now see and enable private mode, but Talos still needs live evidence before private-document positioning.

## Product risk

Marketing Talos as a private paperwork assistant before live private-mode evidence would overclaim safety.

## Runtime boundary affected

REPL command state, protected-read scope, RAG/retrieve defaults, startup warnings, documentation.

## Non-goals

- Automatic private-mode switching.
- Full document extraction.
- Legal, tax, or medical advice claims.

## Required behavior

- Keep `/privacy` UX visible.
- Keep sensitive-folder detection warning-only.
- Do not read protected file contents to produce warnings.
- Add broader private-mode live/e2e scenarios.

## Proposed implementation

Expand `/privacy` integration into general status/help surfaces and add e2e/live prompt-bank coverage.

## Tests

- `PrivacyCommandTest`
- `SensitiveWorkspaceDetectorTest`
- future private-mode e2e prompt-bank scenarios

## Acceptance criteria

- `/privacy` remains documented.
- Sensitive-folder warning remains shallow metadata only.
- Live audit proves private-mode protected reads do not enter model context without explicit send-to-model opt-in.

## Remaining blockers

- Broad private-document/private-mode corpus coverage missing.
- Sensitive paperwork fixtures missing.

## Open questions

- Should sensitive-folder detection eventually suggest private mode during workspace switch as well as startup?

## Related files

- `src/main/java/dev/talos/cli/repl/slash/PrivacyCommand.java`
- `src/main/java/dev/talos/runtime/policy/SensitiveWorkspaceDetector.java`
- `README.md`

## 2026-05-15 final pre-beta update

- `/privacy` status/help now states that command changes are current session/config state only and do not write `~/.talos/config.yaml`.
- README now says to edit `~/.talos/config.yaml` for persistent private-mode defaults.
- `SensitiveWorkspaceDetector` now avoids false positives for `valid-project` and `grid-ui` while still warning for tokenized `id-documents`.
- Initial private-mode scripted e2e coverage was added.
- Follow-up tickets: T287 and T289.

## 2026-05-16 capability audit update

- Focused two-model beta-core capability audit `capability-live-audit-20260516-195820` ran against GPT-OSS and Qwen.
- Private-mode search/status prompts passed the script heuristics.
- This does not make Talos private-document ready; broader tax/health/legal/admin fixtures remain required.
