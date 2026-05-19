# Manual Transcript Synthwave Site Audit

Date: 2026-05-19
Branch: v0.9.0-beta-dev
Commit inspected: ec69415 plus working-tree changes
Candidate version: 0.9.9
Evidence source: user-provided interactive Talos transcript from `C:\Users\arisz\Desktop\testtalos`

## Summary

The transcript exposed a real developer-beta reliability blocker in follow-up mutation handling. Talos behaved correctly for unsupported PDF creation and protected-read refusal in separate evidence, but the synthwave-site workflow showed that natural follow-ups and correction prompts can fall into read-only mode after prior workspace mutation context.

This is not a privacy failure and not an unapproved mutation. It is a task-contract and follow-up intent failure that blocks simple-user and developer trust.

## Confirmed Findings

### F1 - Deictic site creation follow-up was classified read-only

Prompt:

```text
great! now can you create that site?
```

Observed:

- Task contract: `READ_ONLY_QA`
- Mutation allowed: `false`
- Visible tools: read/search/retrieve only
- Talos repeatedly listed/read files and stopped by failure policy.

Expected:

- Mutation-capable contract, because the prompt explicitly asks Talos to create an artifact and refers to a previously created website-planning text file.

Category: runtime-owned task classification bug.
Severity: high.

Regression added:

- `MutationIntentTest.overwriteRewriteReplaceAndNaturalCreationPhrasingAreExplicitMutationIntent`
- `TaskContractResolverTest.createThatSiteFollowUpAfterSourceFileCreationBecomesApplyCapable`

Fix in working tree:

- `MutationIntent` now accepts polite/affirming prefixes with terminal punctuation, including `Great! now can you ...`.

### F2 - Styling correction prompt was classified read-only

Prompt:

```text
But you just changed the index and reduced it. You never put any style in the index
```

Observed:

- Task contract: `READ_ONLY_QA`
- Mutation allowed: `false`
- Talos inspected `index.html`, repeatedly tried missing `style.css`, and stopped by failure policy.

Expected:

- Mutation-capable repair/correction contract, because the user is directly challenging the adequacy of the immediately preceding mutation.

Category: runtime-owned follow-up classification bug.
Severity: high.

Regression added:

- `TaskContractResolverTest.missingStylingCorrectionAfterSiteMutationInheritsApplyCapableContract`
- `TaskContractResolverTest.readOnlyQuestionAboutTxtAfterSiteDiscussionStaysReadOnly`

Fix in working tree:

- `TaskContractResolver.fromMessages(...)` now recognizes narrow styling/correction complaints and inherits the prior mutation contract when the previous user turn was mutation-allowed.

### F3 - Multi-file static site completeness is still weak

Prompt:

```text
make the rest files please according to txt. I need a good modern synthwave style
```

Observed:

- Talos wrote only `index.html`.
- No `style.css` was created.
- Final answer reported only generic write/readback success; no task-specific static verifier was applicable.

Expected:

- For a static web creation request with explicit styling quality, Talos should either create/link CSS or report that the requested site is incomplete.

Category: mixed runtime/model/verifier failure.
Severity: high.
Ticket: T316.

Regression added:

- `StaticTaskVerifierTest.styledWebpageRequestFailsWhenHtmlHasNoInlineOrLinkedStyle`
- `StaticTaskVerifierTest.styledWebpageRequestPassesWhenHtmlHasInlineStyle`
- `StaticTaskVerifierTest.transcriptStyleFollowUpFailsWhenOnlyHtmlWithoutStylingWasMutated`

Fix in working tree:

- `StaticWebCapabilityProfile` now selects static-web verification for styled/visual web tasks when a mutating request names a web surface or mutates HTML.
- `StaticTaskVerifier` now checks partial styled HTML outputs for inline CSS or linked existing CSS before reporting success.

### F4 - Failure-policy final answer is truthful but unhelpful

Observed:

- Repeated no-progress read/list loops ended with a generic failure-policy answer.
- The answer did not explain the actionable correction: the turn was classified read-only, so mutating tools were unavailable.

Expected:

- When no-progress failure occurs on a user request that appears to request mutation, final output should report the classification/tool-surface mismatch.

Category: UX and outcome-rendering bug.
Severity: high.
Ticket: T317.

Regression updated:

- `ToolCallLoopTest.readOnlyDuplicateReadLoopStopsBeforeGenericIterationLimit`

Fix in working tree:

- No-progress failure-policy stop messages now include runtime context:
  task contract, `mutationAllowed`, successful mutation count, and an explicit hint when mutating tools were not available for the turn's contract.

### F5 - PDF creation refusal was correct; PDF reading was not tested

Observed:

- User asked Talos to create a PDF.
- Talos refused to create unsupported binary document output and suggested supported source formats.

Expected:

- This is correct. The transcript did not test reading an actual `.pdf`; it tested reading a Markdown file named `pdf_guide.md`.

Category: audit-design clarification.
Severity: medium.
Ticket: T320.

## Focused Verification Run

After adding failing tests and patching the narrow classification paths:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
```

Result: passed.

After adding styled-web verifier tests and patching the narrow verifier selection path:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.capability.CapabilityProfileRegistryTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest" --no-daemon
```

Result: passed.

After adding runtime context to no-progress failure-policy stops:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyDuplicateReadLoopStopsBeforeGenericIterationLimit" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.capability.CapabilityProfileRegistryTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest" --no-daemon
```

Result: passed.

Important note: an earlier attempt to run two Gradle test invocations in parallel against the same `build/test-results/test/binary` directory caused a file-lock cleanup failure. Do not parallelize Gradle test tasks that write the same output directory.
