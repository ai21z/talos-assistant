# [T415-done-high] Extract Embedded Static Verification Result Parser

## Status

Done.

## Scope

T415 implements the parser boundary selected by T414:

```text
dev.talos.runtime.verification.EmbeddedStaticVerificationResultParser
```

The ticket extracts only the compatibility parser that turns an already-rendered
static-verification failure answer fragment back into `TaskVerificationResult`
state.

T415 does not change final answer wording, dominance policy, static verifier
execution, static verification rendering, evidence-obligation adaptation,
read-only tool-loop-limit handling, protected-read safety, or compatibility
answer shaping.

## What Changed

`EmbeddedStaticVerificationResultParser` now owns:

- detecting `[Task incomplete: Static verification failed - ...]`;
- extracting the rendered static-verification summary;
- extracting `Unresolved static verification problems:` bullet lines;
- returning a failed `TaskVerificationResult` when an embedded failure exists;
- returning `TaskVerificationResult.notRun("Post-apply verification was not applicable.")`
  when no embedded failure exists.

`ExecutionOutcome` still owns:

- deciding when embedded static-verification fallback is considered;
- choosing between `StaticTaskVerifier.verify(...)`, embedded fallback, and
  not-run verification;
- preserving already-rendered blocked-tool-loop answers;
- final answer shaping;
- outcome dominance;
- `TaskOutcome` assembly.

## Behavior Preservation

The extracted parser preserves the previous fallback behavior:

- no marker returns `NOT_RUN`;
- summary extraction still prefers the closing `]`, falling back to line end
  when the bracket is missing;
- blank summaries still become `Static verification failed.`;
- missing problem bullets still fall back to a single problem equal to the
  summary;
- bullet extraction stops after the first nonblank non-bullet line following
  the problem list.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.EmbeddedStaticVerificationResultParserTest" --no-daemon
```

failed at compile time because `EmbeddedStaticVerificationResultParser` did not
exist.

GREEN and focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.EmbeddedStaticVerificationResultParserTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

passed after adding the parser and wiring `ExecutionOutcome`.

## Required Gate

Before integration, run:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Results:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `git diff --check`: passed, with the expected line-ending warning for
  `ExecutionOutcome.java`.
- `.\gradlew.bat check --no-daemon`: passed.

## Next

After T415 integrates cleanly, inspect post-T415 `ExecutionOutcome` before
choosing T416. The likely remaining candidates are evidence-obligation
adaptation, read-only tool-loop-limit truthfulness rendering, or a decision
ticket for the remaining compatibility answer-shaping block. Do not assume the
next implementation slice without source inspection.
