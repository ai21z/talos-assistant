# T852 GPT-OSS Multi-Document No-Progress Synthesis

Status: implemented awaiting live review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Implementation state: pending commit in this changeset

## Source Evidence

- Ticket: `work-cycle-docs/tickets/open/[T852-open-medium] gpt-oss-multi-document-no-progress-synthesis.md`
- T842 scenario: `local/beta-pre-release-test-scenarios/scn-11-mixed-format-analysis`
- Historical failing model/backend: `gpt-oss:20b` through managed `llama.cpp`
- Historical prompt:

```text
Using q3.pdf, budget.xlsx and targets.csv: what was Q3 revenue, and did Engineering spend over or under its target, and by exactly how much? Cite each file.
```

The historical run read all three requested files, then repeatedly re-read
already gathered evidence and stopped with the generic no-progress failure
policy. This was trust-safe because it did not fabricate an answer, but it was
not useful beta behavior for a multi-document read-only question.

## Implemented Behavior

T852 adds a narrow terminal read-only stop in
`runtime.toolcall.TerminalReadOnlyStopAnswer`:

- applies only to `READ_ONLY_QA`;
- requires at least two expected targets;
- requires every expected target to have been read in the current turn;
- requires the current tool-loop iteration to have no successful tool work, no
  failed tool work, and no mutations;
- does not increase loop limits;
- does not ask the model to synthesize unsupported facts;
- returns a bounded evidence-complete failure that lists the requested files
  already read and states that no files were changed.

This preserves the no-progress fail-safe while replacing the generic failure
message with a more specific outcome when the runtime can prove that all
requested read evidence was already gathered.

## Regression Coverage

New deterministic coverage:

- `ToolCallLoopTest.multiTargetReadOnlyDuplicateLoopStopsWithEvidenceCompleteFailure`

The regression builds the scn-11 shape with three requested targets
(`q3.pdf`, `budget.xlsx`, `targets.csv`), scripts repeated duplicate read
attempts, and asserts that:

- the generic failure policy does not own the final result;
- the final answer says `Read evidence complete`;
- the final answer names all three requested files;
- the final answer says the model repeated read calls instead of producing a
  final answer;
- no mutating tool successes are reported.

Focused suites run during implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.multiTargetReadOnlyDuplicateLoopStopsWithEvidenceCompleteFailure" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyDuplicateReadLoopStopsBeforeGenericIterationLimit" --tests "dev.talos.runtime.ToolCallLoopTest.multiTargetReadOnlyDuplicateLoopStopsWithEvidenceCompleteFailure" --tests "dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswerTest" --tests "dev.talos.runtime.toolcall.ToolFailurePolicyStopAnswerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
```

The first red run failed because the old behavior still returned the generic
no-progress failure. The final focused runs passed.

## Non-Claims

- T852 does not prove GPT-OSS now produces a complete cross-document answer.
- T852 does not change retrieval, document extraction, vector search, or ranking.
- T852 does not increase the tool-loop iteration limit.
- T852 does not close broader GPT-OSS multi-document synthesis quality work.
- T852 does not replace the required live scn-11 rerun before ticket closeout.

## Remaining Review Gate

Before closing T852, rerun the T842 scn-11 mixed-format analysis prompt on
GPT-OSS against the current installed build. Acceptable outcomes:

- GPT-OSS produces a grounded answer from the gathered files; or
- Talos stops with the bounded evidence-complete failure instead of the generic
  failure-policy message.

The run must confirm no files changed and no approval/mutation path was entered.
