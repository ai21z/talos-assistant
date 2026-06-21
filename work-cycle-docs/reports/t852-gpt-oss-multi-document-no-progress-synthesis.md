# T852 GPT-OSS Multi-Document No-Progress Synthesis

Status: done
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Implementation commit: `6ec410a71fd2219356e3c80cc624b75be7c67002`

## Source Evidence

- Ticket: `work-cycle-docs/tickets/done/[T852-done-medium] gpt-oss-multi-document-no-progress-synthesis.md`
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

## Live Closeout Evidence

Installed build rerun:

```powershell
.\gradlew.bat installDist --no-daemon
pwsh -NoProfile -ExecutionPolicy Bypass -File .\local\beta-pre-release-test-scenarios\run-scenarios.ps1 -Only scn-11-mixed-format-analysis -Model gpt-oss-20b -ModelPath "C:\Users\arisz\.cache\huggingface\hub\models--ggml-org--gpt-oss-20b-GGUF\snapshots\e1dc459feff949ff451ce107337a2026daa80df8\gpt-oss-20b-mxfp4.gguf" -Port 18121 -Context 16384 -OutSubdir "runs\t852-6ec410a7\gpt-oss-20b" -StopStale
```

Artifact:

```text
local/beta-pre-release-test-scenarios/runs/t852-6ec410a7/gpt-oss-20b/scn-11-mixed-format-analysis/transcript.txt
```

Observed result:

- contract: `READ_ONLY_QA`;
- model/backend: `llama_cpp/gpt-oss-20b`;
- tools: four `talos.read_file` calls;
- final answer: `Read evidence complete, but no final synthesis was produced.`;
- listed gathered files: `budget.xlsx`, `q3.pdf`, `targets.csv`;
- approvals: `required=0 granted=0 denied=0`;
- git status artifact: empty;
- git diff artifact: empty.

T852 therefore satisfies the bounded terminal-outcome acceptance condition. It
does not prove GPT-OSS can now synthesize the full cross-document answer.

## Non-Claims

- T852 does not prove GPT-OSS now produces a complete cross-document answer.
- T852 does not change retrieval, document extraction, vector search, or ranking.
- T852 does not increase the tool-loop iteration limit.
- T852 does not close broader GPT-OSS multi-document synthesis quality work.
- T852 does not replace the required live scn-11 rerun before ticket closeout.

## Closeout

T852 is closed. Future retrieval or synthesis quality work should continue
under T847 or a later scoped ticket.
