# [T567] Extract protected-read postcondition trace event factory

## Summary

T567 extracted protected-read postcondition trace event construction behind the
existing `LocalTurnTraceCapture` facade.

The public trace call remains:

```java
LocalTurnTraceCapture.recordProtectedReadPostcondition(status, paths, reason)
```

The event construction is now owned by package-local
`ProtectedReadPostconditionTraceEventFactory`.

## Scope

Changed:

- added `ProtectedReadPostconditionTraceEventFactory`;
- changed `LocalTurnTraceCapture.recordProtectedReadPostcondition(...)` to
  delegate event construction;
- added `LocalTurnTraceProtectedReadPostconditionTest`;
- added this done ticket.

Preserved:

- event type: `PROTECTED_READ_POSTCONDITION_CHECKED`;
- payload keys: `status`, `pathHints`, `reason`;
- path-hint redaction through `TraceRedactor.pathHint(...)`;
- status and reason trimming/null fallback;
- null path-list fallback;
- public `LocalTurnTraceCapture` facade;
- protected-read policy in `ProtectedReadAnswerGuard`;
- approved-read answer repair;
- protected-history suppression;
- warning selection;
- outcome dominance;
- model-context handoff;
- trace lifecycle and persistence.

Explicitly not changed:

- `ProtectedReadAnswerGuard` policy;
- approved protected-read warning construction;
- `ExecutionOutcome` dominance behavior;
- action-obligation accounting;
- protocol sanitization trace;
- backend malformed response trace;
- exact-write correction trace;
- prompt-debug lifecycle;
- artifact canary scanning.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceProtectedReadPostconditionTest" --no-daemon
```

The ownership regression failed before implementation because
`ProtectedReadPostconditionTraceEventFactory.java` did not exist.

GREEN and focused coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceProtectedReadPostconditionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.outcome.ProtectedReadAnswerGuardTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

Standard gates:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next move

Inspect the post-T567 local trace evidence shape before selecting T568.

Do not assume action-obligation trace extraction is next. It still spans
pending-obligation state, loop terminal failure behavior, repair policy,
source-derived evidence, exact-write fallback, and compact continuation paths.
