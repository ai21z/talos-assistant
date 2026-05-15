# T271 - Prompt-Debug, Trace, Provider-Body, Session, and Logs Redaction Release Gate

Status: open
Severity: high
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Prompt-debug, provider-body JSON, local traces, session snapshots, turn JSONL, and logs are durable artifacts. Current redaction catches some secret-like assignments but misses T267 canaries and private markers.

## Evidence from current code

- `TraceRedactor` redacts secret-like assignments but not `DO_NOT_LEAK*`, `TALOS_CANARY_*`, `CANARY_*`, or `PRIVATE_MARKER`.
- `PromptDebugInspector` uses its own protected content signal and delegates provider-body redaction to `TraceRedactor`.
- `JsonSessionStore` persists turn content and local traces; it does not own comprehensive redaction.
- Live audit Prompt 17 saved raw marker values in prompt-debug/provider-body artifacts.

## Evidence from external/source crosscheck

Codex docs emphasize telemetry/logs for auditability; external assistant-source lessons show debug/prompt/source-map artifacts can become sensitive durable records. Audit artifacts need redaction.

## User impact

A user can avoid final-answer leakage but still leak protected content into local artifacts or provider request bodies.

## Product risk

High release gate for broad beta; P0 if raw content reaches provider body/model context.

## Runtime boundary affected

Prompt-debug markdown, provider-body JSON, local turn trace, session JSON, turn JSONL, runtime logs, final answer.

## Non-goals

- Removing all debugging.
- Hiding evidence that redaction occurred.

## Required behavior

All artifact surfaces redact T267 canaries, private markers, protected path content, and secret-like values.

## Proposed implementation

Make artifact redaction delegate to `ProtectedContentPolicy`. Add generated artifact canary scan.

## Tests

- `provider_body_does_not_contain_raw_canary_after_grep`
- `prompt_debug_does_not_save_raw_canary_after_grep`
- `local_turn_trace_does_not_contain_raw_canary_after_grep`
- `session_turn_log_does_not_contain_raw_canary_after_grep`
- generated artifact canary scan

## Acceptance criteria

- No raw canary appears in disallowed generated artifacts.
- Redaction tests avoid printing raw canaries in failure messages where possible.

## Rollback / migration notes

Existing local artifacts may already contain raw values. Document that old artifacts should be deleted for clean release audits.

## Open questions

- Should Talos provide a `/redact-artifacts` or `/purge-debug-artifacts` command?

## Related files

- `src/main/java/dev/talos/runtime/trace/TraceRedactor.java`
- `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
- `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java`

