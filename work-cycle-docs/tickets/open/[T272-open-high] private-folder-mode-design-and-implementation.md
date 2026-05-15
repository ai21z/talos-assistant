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

Current protected-path behavior is tied to specific path names and direct read approval. It does not provide a user-facing private-folder mode state or stricter defaults for sensitive folders.

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

Design a `private-folder-mode` setting and runtime state that tightens read/search/retrieve behavior. Integrate with `ProtectedContentPolicy` and RAG defaults.

## Tests

- private mode disables indexing by default
- private mode grep returns redacted/count-only results
- private mode read requires explicit approval
- mode status is visible

## Acceptance criteria

- Private-folder mode design doc exists.
- Runtime implementation passes focused tests before sensitive-document beta.

## Rollback / migration notes

Private mode should be opt-in initially unless folder heuristics are highly reliable.

## Open questions

- Should Talos auto-suggest private mode based on folder names such as tax, health, legal, family, admin, passport, insurance, bank?

## Related files

- future design doc under `docs/architecture/`
- runtime policy/tool-surface planner files

