# T272 - Private Folder Mode Design and Implementation

Status: open
Severity: high
Release gate: yes for sensitive-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Even after T267, Talos needs a clear mode for folders likely to contain tax, health, legal, family, admin, or personal paperwork. Generic developer defaults are not enough for sensitive personal folders.

## Evidence from current code

Current protected-path behavior is tied to specific path names and direct read approval. This pass adds a minimal user-facing private mode, but broader live/e2e evidence is still missing for sensitive-folder positioning.

## Evidence from external/source crosscheck

Codex and Gemini both expose permission/sandbox modes. Talos needs a local-first equivalent that is product-specific, not a copy of either tool.

## User impact

Non-technical users may not know that `.env` is protected but `Tax2025/` or `Health/` is not.

## Product risk

Without private-folder mode, Talos should not be marketed as safe for personal paperwork even if T267 redaction improves.

## Runtime boundary affected

Workspace mode classification, indexing defaults, grep/search/retrieve output, approval prompts, user-facing status.

## Non-goals

- Full document extraction.
- Legal/medical/tax advice claims.

## Required behavior

- Stricter defaults for private/sensitive folders.
- No indexing by default in private-folder mode.
- No raw grep lines by default.
- Stronger approval/confirmation before reading private content.
- Visible mode state in status/tool permission explanations.

## Proposed implementation

Implement and expand a `private-folder-mode` setting and runtime state that tightens read/search/retrieve behavior. Integrate with `ProtectedContentPolicy`, RAG defaults, slash commands, and startup warnings.

## Tests

- private mode disables indexing by default
- private mode grep returns redacted/count-only results
- private mode read requires explicit approval
- mode status is visible

## Acceptance criteria

- Private-folder mode design doc exists.
- Runtime implementation passes focused tests before sensitive-document beta.

## Rollback / migration notes

Private mode remains opt-in. Folder heuristics warn only and must not silently switch modes.

## Open questions

- Should Talos auto-suggest private mode based on folder names such as tax, health, legal, family, admin, passport, insurance, bank?

## Related files

- future design doc under `docs/architecture/`
- runtime policy/tool-surface planner files

## 2026-05-15 hardening update

Implemented V1:

- `privacy.mode = private`
- private mode disables RAG retrieval/indexing by default
- approved protected direct reads default to `LOCAL_DISPLAY_ONLY`
- `/privacy status`
- `/privacy private on`
- `/privacy private off`
- `/privacy help`
- warning-only sensitive workspace detection

Still open:

- broader private-mode e2e tests
- two-model live prompt-bank audit
- UX polish for status/help outside the `/privacy` command

This ticket remains a private-document release blocker.

## 2026-05-18 scripted private-folder bank update

Implemented evidence harness support:

- `scripts/run-capability-live-audit.ps1 -BetaCoreOnly -PrivateFolderBank -StopStaleServers`
- private-mode `/show` probes for PDF/DOCX/XLSX local display
- private-mode `/reindex --full` refusal probe
- private-mode retrieve-style probe
- protected direct-read denial probe
- generated `PRIVATE-FOLDER-MANUAL-AUDIT-RUNBOOK.md` for approval-sensitive prompts

Latest run:

- Audit ID: `capability-live-audit-20260518-004603`
- Result: 44/44 scripted prompt runs passed process/tool-artifact heuristics
- Targeted runtime artifact canary scan passed with only source fixtures allowlisted

Bug found and fixed:

- `/show` in private mode could use an existing index snippet after a developer-mode reindex. `ShowCommand` now skips index snippets in private mode unless private-mode RAG is explicitly enabled.

Still open:

- per-turn extracted-document send-to-model approval UX/tracing
- approval grant/deny live transcript capture
- larger real-world private-folder fixtures
- checkpoint/mutation/restore private-folder probes
