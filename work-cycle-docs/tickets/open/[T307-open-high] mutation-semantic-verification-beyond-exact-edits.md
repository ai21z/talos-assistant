# T307 - Mutation Semantic Verification Beyond Exact Edits

Status: open
Severity: high
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

Talos now verifies exact `talos.edit_file` replacement evidence, but many mutation tasks still fall back to `READBACK_ONLY` because the runtime can only prove that a target exists, is readable, and passed file-level syntax/content checks.

That is not enough for broad beta confidence. A file can be readable after mutation while still failing the user's requested semantics.

## Evidence from current code

- `StaticTaskVerifier.verify(...)` promotes exact `talos.edit_file` replacement outcomes to `PASSED` when `ToolCallLoop.MutationEvidence.exactEdit(...)` proves replacement text is present and old text is absent when that absence is meaningful.
- `ToolCallExecutionStage` attaches exact edit evidence for successful `talos.edit_file` calls from `old_string`/`new_string` parameters.
- `TaskExpectationResolver` already handles narrow exact full-file literal expectations, and `StaticTaskVerifier` verifies those as exact content.
- `TaskExpectationResolver` now also derives a narrow `BulletListExpectation` for single-target requests such as "Create notes/generated-summary.md with exactly three bullet points."
- `StaticTaskVerifier` now counts rendered bullet/list lines in the target file and promotes exact bullet-count matches to `PASSED`; mismatched counts or non-blank non-bullet prose fail with deterministic problems instead of falling back to `READBACK_ONLY`.
- `TaskExpectationResolver` now derives a narrow `AppendLineExpectation` for single-target requests such as "Append exactly this line to README.md: Release gate note."
- `StaticTaskVerifier` now verifies that the requested appended line is present exactly once as the final logical line. For successful `talos.edit_file` outcomes with exact mutation evidence, it also rejects rewrites where `new_string` does not preserve `old_string` before the appended line.
- `StaticTaskVerifier` now fails append-line requests satisfied via `talos.write_file` unless the tool loop captured complete same-turn read evidence for the same target before the full-file write. This preserves the fail-closed behavior for unproven whole-file writes while allowing positive append-only proof when the runtime has prior content and the new full content appends only the requested line.
- `ToolCallExecutionStage` now attaches `FULL_WRITE_REPLACEMENT` mutation evidence for successful `talos.write_file` calls only when a complete same-turn `talos.read_file` of the same canonical path was observed before mutation. This does not introduce a hidden pre-approval read; it reuses evidence already returned to the model in the same turn.
- The verifier and tool-loop evidence paths now normalize accepted native-tool aliases before comparing `read_file`, `write_file`, and `edit_file`, so semantic evidence does not depend on whether the model used the `talos.*` name or an accepted local alias.
- `TaskExpectationResolver` now derives `ReplacementExpectation` for narrow "replace X with Y in target" and "change title/text from X to Y in target" requests.
- `StaticTaskVerifier` now verifies those replacement expectations by checking that the new literal is present and the old literal is absent in the post-apply target file.
- `ReplacementExpectation` now carries a narrow `preserveRest` flag when the user explicitly says to preserve/keep/leave the rest unchanged or not change anything else.
- `StaticTaskVerifier` now verifies preserve-rest replacement requests only when mutation evidence proves the final text equals prior text with exactly one requested old-text to new-text replacement. `talos.edit_file` must provide exact edit evidence; `talos.write_file` must provide full-write evidence from a complete same-turn prior read. Plain full writes without prior-content evidence fail closed.
- Preserve-rest full-write verification now tolerates only a single terminal-newline difference between prior-read-derived expected content and model-written content. This is deliberate: complete-read evidence is reconstructed from numbered `read_file` output and cannot prove the original EOF newline state. Any body/content change beyond the requested old/new replacement still fails.
- `ToolCallRepromptStage` now uses `StaticTaskVerifier.verifyWithoutTraceEvents(...)` for internal static-web reprompt probes, so semantic expectation probes do not duplicate `EXPECTATION_VERIFIED` trace events.
- `TaskContractResolver` captures explicit forbidden sibling targets such as `Do not edit scripts.js`, and `StaticTaskVerifier` now fails the mutation when a forbidden target is also changed.
- `TaskContractResolver` now also captures comma-style direct forbidden sibling targets such as `edit only script.js, not scripts.js`, so the expected target remains `script.js` and `scripts.js` becomes a forbidden target instead of a second expected target.
- `StaticTaskVerifier` now fails a single-target mutation when the prompt uses explicit target-only wording such as "Only change script.js" and a non-requested target is also mutated.
- For other tasks, `StaticTaskVerifier` intentionally returns `READBACK_ONLY` with summary `Target/readback checks passed ... no task-specific static verifier was applicable.`

## Evidence from tests/audits

- `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon` passed after adding exact edit evidence tests.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed with exact edit approval scenarios asserting `PASSED`.
- `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed.
- Scripted synchronized approval artifacts now show `mutation-approval-granted-checkpointed` and `mutation-remember-approval-auto-approves-second-write` with `Exact edit replacement verification passed`.
- A regression test confirms a mixed mutation turn with one exact edit and one readback-only write remains `READBACK_ONLY` instead of overclaiming `PASSED`.
- `./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon` passed after adding exact bullet-count expectation and verifier coverage.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed after adding `mutation-exact-bullet-count-verified` to the scripted synchronized approval audit bank.
- `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed; the generated summary reports 14 scripted scenarios and artifact scan PASS.
- `build/synchronized-approval-audit/artifacts/mutation-exact-bullet-count-verified/audit-transcript.json` records `verificationStatus=PASSED`, `checkpointStatus=CREATED`, and `verificationSummary="Bullet count verification passed."`.
- Added regression coverage:
  - `extractsExactBulletCountForSingleTarget`
  - `exactBulletCountExpectationPassesWhenGeneratedTargetHasRequestedCount`
  - `exactBulletCountExpectationFailsWhenGeneratedTargetHasWrongCount`
- `./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon` passed after adding append-line expectation, verifier, trace-redaction, and mutation-classification coverage.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed after adding `mutation-append-line-verified` to the scripted synchronized approval audit bank.
- `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed; the generated summary reports 16 scripted scenarios and artifact scan PASS.
- `build/synchronized-approval-audit/artifacts/mutation-append-line-verified/audit-transcript.json` records `verificationStatus=PASSED`, `checkpointStatus=CREATED`, and `verificationSummary="Append line verification passed."`.
- `build/synchronized-approval-audit/artifacts/mutation-replacement-verified/audit-transcript.json` records `verificationStatus=PASSED`, `checkpointStatus=CREATED`, and `verificationSummary="Replacement verification passed."`.
- `build/synchronized-approval-audit/artifacts/mutation-append-line-verified/traces/last-trace.json` records one `EXPECTATION_VERIFIED` event after the silent-probe fix.
- Fresh full verification after the append-line and silent-probe slice passed:
  - `./gradlew.bat clean check e2eTest --no-daemon`
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon`
  - `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon`
  - `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-approval-audit/artifacts" --no-daemon`
  - `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon`
  - direct raw-value sweep over generated audit artifacts, reports, tickets, build reports, and test results found no protected/private audit canaries
  - `git diff --check` passed with CRLF normalization warnings only
- Added regression coverage:
  - `appendLineRequestBecomesFileEditContract`
  - `extractsAppendLineExpectationForSingleTarget`
  - `appendLineExpectationPassesWhenLineIsLastLogicalLine`
  - `appendLineExpectationFailsWhenWriteFileCannotProveAppendOnlyPreservation`
  - `appendLineExpectationFailsWhenExactEditRewritesExistingContent`
  - `appendLineExpectationFailsWhenLineMissing`
  - `appendLineExpectationFailsWhenLineDuplicated`
  - `appendLineExpectationFailsWhenLineIsNotLastLogicalLine`
  - `appendLineExpectationTraceEventIsRedacted`
- `./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon` passed after adding exact-edit append-only preservation rejection.
- `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon` passed after adding explicit forbidden sibling-target verification.
- Added regression coverage:
  - `explicitForbiddenSiblingTargetIsCaptured`
  - `forbiddenSimilarTargetMutationFailsEvenWhenExpectedTargetMutated`
- `./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon` passed after adding replacement expectation, target-only, and strict bullet-only coverage.
- Added regression coverage:
  - `extractsReplacementExpectationForSingleTarget`
  - `extractsChangeFromToReplacementExpectationForSingleTarget`
  - `replacementExpectationPassesWhenOldRemovedAndNewPresentAfterWrite`
  - `replacementExpectationFailsWhenOldTextRemains`
  - `replacementExpectationFailsWhenNewTextMissing`
  - `replacementExpectationTraceEventIsRedacted`
  - `onlyTargetRequestFailsWhenAdditionalSiblingTargetMutated`
  - `exactBulletCountExpectationFailsWhenGeneratedTargetHasExtraProse`
- Fresh verification after the replacement, target-only, and strict bullet-only slice passed:
  - `./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon`
  - `./gradlew.bat clean check e2eTest --no-daemon`
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon`
  - runtime artifact scans over `build/reports,build/test-results`, `build/synchronized-approval-audit/artifacts`, and `work-cycle-docs/reports,work-cycle-docs/tickets`
  - direct raw-value sweep over generated audit artifacts, reports, tickets, build reports, and test results found no protected/private audit canaries
  - `git diff --check` passed with CRLF normalization warnings only
- Fresh full verification after the forbidden-target slice passed:
  - `./gradlew.bat clean check e2eTest --no-daemon`
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon`
  - runtime artifact scans over `build/reports,build/test-results`, `build/synchronized-approval-audit/artifacts`, and `work-cycle-docs/reports,work-cycle-docs/tickets`
  - direct raw-value sweep over generated audit artifacts, reports, tickets, build reports, and test results found no protected/private audit canaries
  - `git diff --check` passed with CRLF normalization warnings only
- Fresh verification after write-file append-only false-success removal:
  - `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon` passed.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed after changing the scripted append-line scenario from `talos.write_file` to exact `talos.edit_file` evidence.
  - `./gradlew.bat clean check e2eTest --no-daemon` passed.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed.
  - Runtime artifact scans passed over `build/reports,build/test-results`, `build/synchronized-approval-audit/artifacts`, and `work-cycle-docs/reports,work-cycle-docs/tickets`.
  - Direct raw-value sweep over generated audit artifacts, reports, tickets, build reports, and test results found no protected/private audit canaries.
  - `git diff --check` passed with CRLF normalization warnings only.
- Fresh focused verification after adding positive full-write append proof from same-turn read evidence:
  - `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.writeFileOutcomeCarriesFullWriteEvidenceWhenWritePathHasDotSlash" --no-daemon` failed before the canonical path fix because `./README.md` write paths did not match prior `README.md` read signatures.
  - `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.writeFileOutcomeCarriesFullWriteEvidenceWhenWritePathHasDotSlash" --no-daemon` passed after canonicalizing the write path at the read-evidence join.
  - `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.writeFileOutcomeCarriesFullWriteEvidenceWhenModelUsesAcceptedToolAliases" --no-daemon` failed before the alias fix because accepted `read_file`/`write_file` aliases did not participate in full-write evidence matching.
  - `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.writeFileOutcomeCarriesFullWriteEvidenceWhenModelUsesAcceptedToolAliases" --rerun-tasks --no-daemon` passed after making the full-write evidence path use `ToolAliasPolicy.localCanonicalName(...)`.
  - `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.exactEditReplacementEvidencePassesWhenAcceptedToolAliasUsed" --no-daemon` passed after exact-edit semantic verification was made alias-aware.
  - `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon` passed after waiting for a separate concurrent Gradle process to release `build/test-results/test/binary/output.bin`.
  - `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.ToolCallLoopTest" --rerun-tasks --no-daemon` passed.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result" --no-daemon` failed before the scripted audit bank included the full-write append scenario.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result" --no-daemon` passed after adding `mutation-append-line-full-write-verified`.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed.
  - `./gradlew.bat e2eTest --tests "*SynchronizedApproval*" --no-daemon` passed.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed; the generated summary included the full-write append scenario and artifact scan PASS. Later T306 expansions raised the scripted bank to 20 scenarios.
  - `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-approval-audit/artifacts" --no-daemon` passed.
  - `build/synchronized-approval-audit/artifacts/mutation-append-line-full-write-verified/audit-transcript.json` records `verificationStatus=PASSED`, `checkpointStatus=CREATED`, and `verificationSummary="Append line verification passed."`.
  - Added regression coverage:
    - `appendLineExpectationPassesWhenFullWriteEvidencePreservesPriorContent`
    - `appendLineExpectationFailsWhenFullWriteEvidenceRewritesPriorContent`
    - `writeFileOutcomeCarriesFullWriteEvidenceWhenTargetWasReadThisTurn`
    - `writeFileOutcomeCarriesFullWriteEvidenceWhenWritePathHasDotSlash`
    - `writeFileOutcomeCarriesFullWriteEvidenceWhenModelUsesAcceptedToolAliases`
    - `exactEditReplacementEvidencePassesWhenAcceptedToolAliasUsed`
    - `deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result` now asserts the full-write append scenario is in the audit summary and records a passed transcript.
- Fresh focused verification after comma-style similar-target wording:
  - `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest.commaNotSimilarTargetWordingCapturesForbiddenTarget" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest.extractsReplacementExpectationAfterApprovalSimilarTargetWording" --no-daemon` failed before the contract fix: `not scripts.js` was not captured as forbidden, and replacement expectation resolution returned no single-target expectation.
  - The same focused resolver tests passed after adding direct `not <file>` forbidden-target extraction.
  - `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon` passed.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result" --no-daemon` failed before the scripted bank included `mutation-similar-target-script-only-verified`.
  - The same focused e2e test passed after adding the similar-target scenario.
  - `./gradlew.bat e2eTest --tests "*SynchronizedApproval*" --no-daemon` passed.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed with 20 scenarios and artifact scan PASS.
  - `build/synchronized-approval-audit/artifacts/mutation-similar-target-script-only-verified/audit-transcript.json` records `verificationStatus=PASSED`, `verificationSummary="Replacement verification passed."`, and `checkpointStatus=CREATED`.
  - `build/synchronized-approval-audit/artifacts/mutation-similar-target-script-only-verified/workspace/diff.txt` records only `M script.js`; `scripts.js` remains unchanged.
- Fresh forbidden-sibling blocked-tool verification after the similar-target slice:
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result" --no-daemon` failed before the scripted bank included `mutation-forbidden-sibling-target-blocked-before-approval`.
  - A deliberately wrong first hypothesis expected a second approval and verifier failure; runtime evidence showed the stronger behavior: the forbidden `scripts.js` call was blocked before approval.
  - The focused e2e test passed after changing the scenario to assert one approved `script.js` edit, `traceStatus=PARTIAL`, `verificationStatus=PASSED` for the allowed replacement, `TOOL_CALL_BLOCKED` for the forbidden sibling, unchanged `scripts.js`, and a diff containing only `M script.js`.
  - `./gradlew.bat e2eTest --tests "*SynchronizedApproval*" --no-daemon` passed.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed with 21 scenarios and artifact scan PASS.
- Fresh focused verification after the preserve-rest replacement slice:
  - `./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest.extractsPreserveRestReplacementExpectationForSingleTarget" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.replacementPreserveRestPassesWhenFullWriteEvidenceOnlyReplacesRequestedText" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.replacementPreserveRestFailsWhenFullWriteEvidenceChangesOtherContent" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.replacementPreserveRestFailsWhenWriteFileHasNoPriorContentEvidence" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.replacementPreserveRestPassesWhenExactEditEvidenceOnlyReplacesRequestedText" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.replacementPreserveRestFailsWhenExactEditEvidenceChangesOtherContent" --no-daemon` failed before production support because `ReplacementExpectation.preserveRest()` did not exist.
  - The same focused tests passed after adding the flag, resolver phrase detection, and evidence-based preservation checks.
  - `./gradlew.bat test --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon` passed.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result" --no-daemon` passed after adding `mutation-preserve-rest-replacement-verified`.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed with the preserve-rest scenario included.
  - `build/synchronized-approval-audit/artifacts/mutation-preserve-rest-replacement-verified/audit-transcript.json` records `verificationStatus=PASSED`, `verificationSummary="Replacement verification passed."`, and `checkpointStatus=CREATED`.
  - `build/synchronized-approval-audit/artifacts/mutation-preserve-rest-replacement-verified/workspace/diff.txt` shows only the title line changing from `Old Portal` to `New Portal`; the body line remains `Keep this.`.
- Fresh two-model 19-case synchronized live verification after read-then-mutation, placeholder, and terminal-newline hardening:
  - GPT-OSS passed:
    `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditMode=live" "-PapprovalAuditConfig=$env:USERPROFILE\.talos\config.yaml" "-PapprovalAuditArtifactsRoot=local/manual-testing/synchronized-approval-live-gptoss-20260519-19case-r3" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/synchronized-approval-live-gptoss-20260519-19case-r3" --no-daemon`
  - Qwen passed:
    `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditMode=live" "-PapprovalAuditConfig=local/manual-testing/synchronized-approval-live-qwen-20260518-0810/qwen-config.yaml" "-PapprovalAuditArtifactsRoot=local/manual-testing/synchronized-approval-live-qwen-20260519-19case-r6" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/synchronized-approval-live-qwen-20260519-19case-r6" --no-daemon`
  - Both summaries report `Scenarios: 19` and `Artifact scan: PASS`.
  - Qwen `mutation-append-line-verified/audit-transcript.json` records `verificationStatus=PASSED`, `verificationSummary="Append line verification passed."`, and `checkpointStatus=CREATED`.
  - Qwen `mutation-preserve-rest-replacement-verified/audit-transcript.json` records `verificationStatus=PASSED`, `verificationSummary="Replacement verification passed."`, and `checkpointStatus=CREATED`.
  - Regression tests added:
    - `readThenReplaceInNamedFileBecomesMutationAllowedContract`
    - `readThenUpdateMeQuestionStaysReadOnly`
    - `replacementPreserveRestToleratesSingleTerminalNewlineDifferenceFromReadEvidence`
    - `leadingToolResultPlaceholderWithAppendedContentIsFlagged`
    - `leadingBracedTemplateVariableWithAppendedContentIsFlagged`
    - `writeFileWithLeadingToolResultPlaceholderIsRejectedBeforeApproval`
    - `writeFileWithLeadingBracedTemplateVariableIsRejectedBeforeApproval`

## User impact

Users can trust exact replacement edits more than before, but they still should not interpret every successful mutation as semantically complete. Tasks such as "create exactly three bullet points", "append one line", "edit only one file", "change the title but preserve the rest", or "fix the static web bug" need task-specific verification beyond readback.

## Product risk

High. Talos's product promise is evidence-backed completion, not plausible completion. Overusing `READBACK_ONLY` weakens the trust story and makes full audits harder to interpret.

## Runtime boundary affected

Mutation verification, outcome rendering, local traces, session summaries, full prompt-bank audit classification, and release evidence.

## Non-goals

- Do not ask the model to self-certify success.
- Do not replace deterministic verification with fluent final-answer wording.
- Do not broaden verification by reading unrelated files.
- Do not run arbitrary shell commands to prove semantics.

## Required behavior

- Exact edit replacements must stay verified as `PASSED` when post-apply evidence supports them.
- Non-exact mutation tasks should remain `READBACK_ONLY` until a deterministic verifier exists.
- Each new semantic verifier must be narrow, deterministic, and covered by tests.
- Final answers and traces must distinguish `PASSED`, `FAILED`, `UNAVAILABLE`, and `READBACK_ONLY` honestly.

## Proposed implementation

Add small verifier slices, one at a time:

1. Append-line verifier: exact final-line support is implemented, exact `talos.edit_file` append evidence rejects rewrites that do not preserve prior content before the appended line, and `talos.write_file` append-line attempts pass only when complete same-turn read evidence proves the full-file replacement preserved prior content and appended only the requested line. Whole-file writes without that evidence still fail closed.
2. Bullet-count verifier: prove generated Markdown contains the requested number of bullet/list items when wording says "exactly". Exact count and strict no-extra-prose support are implemented for narrow bullet/list outputs.
3. Similar-target guard: explicit forbidden sibling-target mutation is implemented for prompts that say not to edit the sibling. Single-target "only change/edit/write this file" wording is also implemented for narrow expected-target tasks.
4. Title/text replacement verifier: initial support is implemented through `ReplacementExpectation` for "replace X with Y in target" and narrow "change title/text from X to Y in target" wording.
5. Preserve-rest replacement verifier: implemented for explicit preserve/keep/leave-rest wording when exact edit evidence or full-write evidence proves only the requested text changed.
6. Static web semantic verifier extensions only where the code already has a small HTML/CSS/JS surface.

## Tests

- append_one_line_verifies_new_line_at_eof - added
- append_one_line_with_write_file_fails_because_append_only_preservation_is_unproven - added
- append_one_line_with_full_write_evidence_passes_when_prior_content_preserved - added
- append_one_line_with_full_write_evidence_fails_when_prior_content_rewritten - added
- write_file_after_same_turn_read_carries_full_write_evidence - added
- write_file_after_same_turn_read_carries_full_write_evidence_for_dot_slash_path - added
- append_one_line_with_exact_edit_fails_when_prior_content_rewritten - added
- append_one_line_fails_when_line_missing - added
- append_one_line_fails_when_line_duplicated - added
- append_one_line_fails_when_line_not_at_eof - added
- append_line_trace_event_redacts_raw_line - added
- exactly_three_bullets_passes_markdown_count - added
- exactly_three_bullets_fails_extra_bullet_or_extra_prose - added
- similar_target_only_requested_file_changed - added for narrow single-target "only" wording
- comma_not_similar_target_wording_keeps_forbidden_sibling_out_of_expected_targets - added
- replacement_expectation_survives_after_approval_similar_target_wording - added
- synchronized_audit_similar_target_script_only_records_passed_verification - added
- synchronized_audit_forbidden_sibling_tool_call_is_blocked_before_approval - added
- explicit_forbidden_similar_target_fails_when_mutated - added
- title_replacement_passes_when_old_removed_and_new_present - added through replacement expectation/verifier coverage
- title_replacement_fails_when_old_text_remains - added through replacement expectation/verifier coverage
- preserve_rest_replacement_passes_with_exact_edit_evidence - added
- preserve_rest_replacement_fails_when_exact_edit_changes_other_content - added
- preserve_rest_replacement_passes_with_full_write_evidence - added
- preserve_rest_replacement_fails_when_full_write_changes_other_content - added
- preserve_rest_replacement_fails_when_write_file_has_no_prior_content_evidence - added
- synchronized_audit_semantic_mutation_scenarios_record_passed_or_failed_not_readback - partial: positive bullet, exact append-line, full-write append-line with same-turn read evidence, replacement, preserve-rest replacement, similar-target, and forbidden-sibling blocked-tool cases are in the scripted audit bank
- mixed_exact_edit_and_readback_only_mutation_does_not_overclaim_passed_verification - added

## Acceptance criteria

- Focused verifier tests pass.
- Relevant synchronized scripted scenarios prove stronger statuses where applicable.
- Full `clean check e2eTest` passes before candidate review.
- Reports clearly list which mutation families are semantically verified and which still fall back to `READBACK_ONLY`.

## Remaining blockers

- True PTY/JLine smoke is still tracked by T306.
- Full prompt-bank integration is still open.
- Positive append-only/no-rewrite verification is now implemented for full-file writes only when the same turn already performed a complete read of the same canonical target before mutation. It remains open for `talos.write_file` calls with no complete same-turn prior read, truncated reads, partial/offset reads, or broader preservation claims.
- Broader preservation verification is now implemented only for explicit old-text/new-text replacement tasks with exact mutation evidence or full-write evidence. It remains open for semantic rewrites where the requested change is not expressible as one old/new literal replacement, for truncated reads, and for broad "preserve the rest" claims after multi-step transformations. A single EOF-newline difference is no longer treated as preservation failure because current read evidence cannot prove that byte-level state.

## Open questions

- Should semantic verifier facts be attached directly to `ToolOutcome`, a separate `MutationEvidence` hierarchy, or task-expectation records?
- How much pre-mutation state should be captured outside checkpoints for verifier use without duplicating checkpoint storage?

## Related files

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/expectation/AppendLineExpectation.java`
- `src/main/java/dev/talos/runtime/expectation/BulletListExpectation.java`
- `src/main/java/dev/talos/runtime/expectation/TaskExpectationResolver.java`
- `src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditRunnerTest.java`
