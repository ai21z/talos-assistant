# T275 - Approved Protected Read Scope Control

Status: open - runtime V1 and minimal UX implemented, live-audit coverage still required
Severity: P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Approval is not the same as privacy safety. An approved direct protected read may intentionally send raw protected content into model context unless Talos separates local display, model-context use, and raw artifact persistence.

## Evidence from current code

- `ProtectedReadScopePolicy` defines private-mode/default scope behavior.
- `ToolCallExecutionStage` withholds approved protected read output from model-loop messages when policy does not allow send-to-model.
- Developer/default mode still allows approved protected direct reads to reach model context for compatibility.

## Evidence from tests/audits

- `ProtectedReadScopePolicyTest`
- `ProtectedReadScopeIntegrationTest`

## User impact

Users may approve reading a private file without understanding whether the content is only displayed locally or also sent to model context.

## Product risk

P0 for any tax/health/legal/family/admin private-document positioning.

## Runtime boundary affected

Protected direct-read approval, model context, provider-body capture, prompt-debug, session persistence.

## Non-goals

- No claim that developer/default mode prevents approved protected content from reaching model context.
- No raw artifact persistence by default.

## Required behavior

- Private mode defaults approved protected reads to `LOCAL_DISPLAY_ONLY`.
- `SEND_TO_MODEL_CONTEXT` requires explicit policy/config.
- Raw persistence remains disabled by default.
- Approval copy explains the scope.

## Proposed implementation

Runtime V1 is implemented. Add user-facing `/privacy` UX and live-audit cases.
Minimal `/privacy` UX is implemented in this pass; live-audit cases remain.

## Tests

- `approved_protected_read_local_display_only_does_not_enter_model_context`
- `approved_protected_read_send_to_model_requires_explicit_scope`
- `approved_protected_read_persistence_is_redacted`
- `private_mode_approved_protected_read_is_withheld_from_model_context`
- `developer_mode_approved_protected_read_can_reach_model_context_explicit_risk`
- `private_mode_send_to_model_requires_explicit_opt_in`
- `private_mode_send_to_model_opt_in_allows_handoff_but_persistence_redacts`
- `persist_raw_artifacts_false_even_when_send_to_model_true`

## Acceptance criteria

- Focused tests pass.
- Live audit proves private/local-display-only scope prevents model-context leakage.
- README/docs do not overclaim.

## Rollback / migration notes

Developer/default mode preserves compatibility; private mode tightens behavior.

## Open questions

- Should developer/default mode eventually switch to local-display-only by default?

## Related files

- `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `README.md`
