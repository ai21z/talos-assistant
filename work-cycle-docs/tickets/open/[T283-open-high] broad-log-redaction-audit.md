# T283 - Broad Log Redaction Audit

Status: open
Severity: high / P0 for sensitive beta
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Helper methods are not proof that every log call is safe. Runtime logs may still expose raw user queries, protected paths, provider exception messages, command details, or model text if call sites bypass redaction.

## Evidence from current code

This pass adds `SafeLogFormatter` and routes several tool execution, parser, RAG, indexer, and tool exception logs through it. Grep still finds remaining log sites in providers, session store, CLI diagnostics, and mode retry paths that need deeper review.

## Evidence from tests/audits

`SensitiveLogRedactionTest` covers tool params, malformed payloads, protected paths, command output canaries, and exception-message redaction.

## User impact

Sensitive user strings should not persist in logs just because a tool failed or a provider returned an error.

## Product risk

Raw logs undermine local trust even when final answers and prompt-debug artifacts are redacted.

## Runtime boundary affected

Tool execution logs, parser logs, provider logs, RAG/index logs, session/trace persistence, command logs.

## Non-goals

- Removing all diagnostics.
- Hiding local approval prompts from the user.

## Required behavior

- Classify every `LOG.debug/info/warn/error` call.
- Redact tool parameters, protected paths, command output, provider body previews, and exception messages.
- Keep a report of fixed versus ticketed call sites.

## Proposed implementation

Continue converting risky call sites to `SafeLogFormatter` or more specific structured summaries.

## Tests

- `SensitiveLogRedactionTest`
- future log-capture tests for provider, RAG trace, command plan, and session persistence logs

## Acceptance criteria

- `work-cycle-docs/reports/log-redaction-audit.md` lists every risky class and disposition.
- No raw `FILE_DISCOVERED_CANARY` appears in generated log artifacts during focused tests.

## Remaining blockers

- Broad provider/session/CLI log-capture tests are not complete.

## Open questions

- Should Talos adopt a structured safe logging wrapper and ban raw `LOG.*` for runtime classes?

## Related files

- `src/main/java/dev/talos/runtime/policy/SafeLogFormatter.java`
- `src/test/java/dev/talos/runtime/policy/SensitiveLogRedactionTest.java`
- `work-cycle-docs/reports/log-redaction-audit.md`

