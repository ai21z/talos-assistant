package dev.talos.runtime;

import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.runtime.policy.WorkStatusAnswerPolicy;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.talos.runtime.TurnRecord.ToolCallDisposition.*;
import static dev.talos.runtime.WorkStatusEvidence.Category.*;
import static org.junit.jupiter.api.Assertions.*;

/** Constructed audits and a recording provider fixture are used. No live tools or models are exercised. */
class WorkStatusEvidenceTest {
    private static final java.nio.file.Path TARGET_WORKSPACE = java.nio.file.Path.of("work-status-target-fixture");
    private static final WorkStatusRequest TARGET_REQUEST = WorkStatusRequest.parse(
            "Give me a short handoff. What was denied earlier? What actually changed? What remains unverified?");

    @Test void partialHandoffRetainsFailuresAndEffectsBeyondTheTargetRowBound() {
        var memory = new SessionMemory();
        var calls = new java.util.ArrayList<TurnRecord.ToolCallSummary>();
        for (int index = 0; index < 13; index++) {
            String target = "saved-" + index + ".txt";
            calls.add(new TurnRecord.ToolCallSummary("talos.write_file", target, List.of(), true, "",
                    ToolMutationEffects.applied(target), EXECUTED));
        }
        calls.add(new TurnRecord.ToolCallSummary("talos.write_file", "denied.txt", List.of(), false, PRIVATE_REASON,
                ToolMutationEffects.none(), BLOCKED, dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED));
        calls.add(new TurnRecord.ToolCallSummary("talos.edit_file", "failed.txt", List.of(), false, PRIVATE_REASON,
                new ToolMutationEffects(List.of("applied.txt"), List.of("uncertain.txt")), EXECUTED,
                dev.talos.tools.ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND));
        listener(memory).onTurnComplete(result(1, targetAudit(calls, ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed mixed results");
        var request = WorkStatusRequest.parse("Give me a short handoff. What is saved and verified, "
                + "and what has not been checked? Don't change any files.");
        String answer = WorkStatusAnswerPolicy.render(request, memory, TARGET_WORKSPACE);
        assertEquals(WorkStatusRequest.Coverage.PARTIAL, request.coverage());
        assertTrue(answer.contains("14 applied, 1 uncertain"), answer);
        assertTrue(answer.contains("2 unsuccessful calls"), answer);
        assertTrue(answer.contains("1 applied entries from unsuccessful calls"), answer);
        assertTrue(answer.contains("USER_APPROVAL_DENIED: 1") && answer.contains("EDIT_OLD_STRING_NOT_FOUND: 1"), answer);
        assertTrue(answer.contains("6 output-omitted entries"), answer);
        assertTrue(answer.contains("Request coverage remains incomplete"), answer);
        assertTrue(answer.contains("not per-file certification"), answer);
        assertFalse(answer.contains(PRIVATE_REASON), answer);
    }

    @Test void partialHandoffCannotRestoreStatusProofFromAssistantProse() {
        var memory = new SessionMemory();
        memory.update("Save the document", "The document was saved and every test passed.");
        var request = WorkStatusRequest.parse("Give me a short handoff. What is saved and verified?");
        String answer = WorkStatusAnswerPolicy.render(request, memory, TARGET_WORKSPACE);
        assertTrue(answer.contains("No retained verification records are available"), answer);
        assertTrue(answer.contains("Request coverage remains incomplete"), answer);
        assertFalse(answer.contains("document was saved") || answer.contains("every test passed"), answer);
    }

    @Test void partialHandoffBoundsTurnLabelsWithoutDroppingRetentionGaps() {
        var memory = new SessionMemory();
        var calls = java.util.Collections.nCopies(801,
                call("talos.read_file", true, EXECUTED, ToolMutationEffects.none()));
        for (int turn = 1; turn <= 201; turn++) {
            listener(memory).onTurnComplete(result(turn, audit(turn == 201 ? calls : List.of(), "FAILED",
                    ContentRestrictions.UNRESTRICTED, WorkStatusReceipt.none())), "Constructed retained turn");
        }
        var request = WorkStatusRequest.parse("Give me a short handoff. What is saved and verified?");
        String answer = WorkStatusAnswerPolicy.render(request, memory, TARGET_WORKSPACE);
        assertTrue(answer.contains("FAILED"), answer);
        assertTrue(answer.contains("188 additional turn labels omitted"), answer);
        assertTrue(answer.contains("1 calls omitted, 1 turns evicted"), answer);
        assertFalse(answer.contains("Turn 201:"), answer);
        assertTrue(answer.length() < 2000, answer);
    }

    @Test
    void sameTurnTargetsKeepDeclarationEffectsAndFailureSeparateWithoutJoiningHistory() {
        var memory = new SessionMemory();
        var denied = new TurnRecord.ToolCallSummary("talos.edit_file", "requested.md", List.of(), false,
                PRIVATE_REASON, ToolMutationEffects.none(), BLOCKED, dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED);
        var failed = new TurnRecord.ToolCallSummary("talos.edit_file", "declared.md", List.of(), false,
                PRIVATE_REASON, new ToolMutationEffects(List.of("applied.md"), List.of("uncertain.md")),
                EXECUTED, dev.talos.tools.ToolFailureReason.NONE);
        listener(memory).onTurnComplete(result(1, targetAudit(List.of(denied), ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed denial");
        listener(memory).onTurnComplete(result(2, targetAudit(List.of(failed), ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed partial effect");
        var retained = memory.workStatusEvidence().turns().getLast().calls().getFirst();
        assertEquals("talos.edit_file", retained.canonicalToolName());
        assertEquals("declared.md", retained.declaredTarget());
        assertEquals(List.of("applied.md"), retained.appliedTargets());
        assertEquals(List.of("uncertain.md"), retained.uncertainTargets());
        assertFalse(retained.reportedSuccess());
        assertEquals(1, retained.appliedEntries());
        assertEquals(1, retained.uncertainEntries());
        String answer = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, TARGET_WORKSPACE);
        for (String path : List.of("requested.md", "declared.md", "applied.md", "uncertain.md"))
            assertTrue(answer.contains("`" + path + "`"), answer);
        assertTrue(answer.contains("BLOCKED, unsuccessful, USER_APPROVAL_DENIED"), answer);
        assertTrue(answer.contains("EXECUTED, unsuccessful, NONE"), answer);
        assertTrue(answer.contains("Applied entries from unsuccessful calls: 1"), answer);
        assertTrue(answer.contains("Declared targets are not applied effects"), answer);
        assertTrue(answer.contains("Operation details remain unresolved"), answer);
        assertFalse(memory.workStatusEvidence().toString().contains(PRIVATE_REASON));
        assertFalse(answer.contains(PRIVATE_REASON));
        assertThrows(UnsupportedOperationException.class, () -> retained.appliedTargets().clear());
    }

    @Test
    void everyRequiredDestinationPermissionIsCheckedBeforeTargetsAreRetained() {
        for (var restrictions : List.of(new ContentRestrictions(false, true, true, true),
                new ContentRestrictions(true, false, true, true), new ContentRestrictions(true, true, false, true),
                new ContentRestrictions(false, false, false, false))) {
            var memory = new SessionMemory();
            var supplied = new TurnRecord.ToolCallSummary("talos.edit_file", "destination-canary.md", List.of(),
                    false, PRIVATE_REASON, ToolMutationEffects.applied("destination-canary.md"), EXECUTED);
            listener(memory).onTurnComplete(result(1, targetAudit(List.of(supplied), restrictions,
                    JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed restricted turn");
            var call = memory.workStatusEvidence().turns().getFirst().calls().getFirst();
            assertEquals(0, call.retainedTargets(), restrictions.toString());
            assertTrue(call.targetMetadataWithheld());
            assertEquals(2, call.withheldTargets());
            assertEquals(1, call.appliedEntries());
            String answer = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, TARGET_WORKSPACE);
            assertFalse((memory.workStatusEvidence() + answer).contains("destination-canary"));
            assertTrue(answer.contains("2 withheld entries"), answer);
        }
        var indexOnly = WorkStatusEvidence.from(result(1, targetAudit(List.of(new TurnRecord.ToolCallSummary(
                "talos.edit_file", "index-only.md", List.of(), true, "", ToolMutationEffects.none(), EXECUTED)),
                new ContentRestrictions(true, true, true, false), JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))));
        assertEquals("index-only.md", indexOnly.calls().getFirst().declaredTarget());
    }

    @Test
    void unsafeTargetsAreOmittedIntactRatherThanRedactedIntoDifferentIdentities() {
        var unsafe = List.of(".env", "[path]", "[secret]", "<protected-path>/" + "a".repeat(64),
                "b".repeat(64), "docs/a\u202Eb.txt", "docs/a\nb.txt", "docs/sk-" + "x".repeat(24) + ".txt",
                "C:/Users/private/note.txt", "../escape.txt", "docs//alias.txt", "docs/name.", "docs/\uD800.txt");
        String admitted = "docs/[visible].md";
        var paths = new java.util.ArrayList<>(unsafe);
        paths.add(admitted);
        var memory = new SessionMemory();
        var supplied = new TurnRecord.ToolCallSummary("talos.edit_file", admitted, List.of(), false, PRIVATE_REASON,
                new ToolMutationEffects(paths, List.of()), EXECUTED);
        listener(memory).onTurnComplete(result(1, targetAudit(List.of(supplied), ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed unsafe paths");
        var call = memory.workStatusEvidence().turns().getFirst().calls().getFirst();
        assertEquals(admitted, call.declaredTarget());
        assertEquals(List.of(admitted), call.appliedTargets());
        assertEquals(unsafe.size(), call.withheldTargets());
        assertEquals(paths.size(), call.appliedEntries());
        String answer = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, TARGET_WORKSPACE);
        assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal(admitted)), answer);
        assertFalse(answer.contains(admitted), "Path formatting must be emitted as inert literal data");
        for (String path : unsafe) assertFalse(memory.workStatusEvidence().toString().contains(path), path);
    }

    @Test
    void targetRenderingUsesReadableSafeNamesAndEscapesMarkupWithoutChangingRetainedIdentity() {
        var safe = List.of("index.html", "styles/main_file-v2.css", "docs/read me.md");
        var markup = List.of("docs/`close`.md", "docs/[label](destination).md", "docs/<b>name</b>.md",
                "docs/**claim**.md", "docs/évidence.md");
        var paths = new java.util.ArrayList<>(safe);
        paths.addAll(markup);
        var memory = new SessionMemory();
        var supplied = new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", List.of(), true, "",
                new ToolMutationEffects(paths, List.of()), EXECUTED);
        listener(memory).onTurnComplete(result(1, targetAudit(List.of(supplied), ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed literal paths");
        assertEquals(paths, memory.workStatusEvidence().turns().getFirst().calls().getFirst().appliedTargets());
        String answer = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, TARGET_WORKSPACE);
        for (String path : safe) assertTrue(answer.contains("`" + path + "`"), answer);
        for (String path : markup) {
            assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal(path)), answer);
            assertFalse(answer.contains("`" + path + "`"), answer);
        }
        assertFalse(answer.contains("`close`"), answer);
        assertFalse(answer.contains("[label](destination)"), answer);
        assertFalse(answer.contains("<b>"), answer);
        assertFalse(answer.contains("**claim**"), answer);
        assertTrue(answer.contains("Operation details remain unresolved"), answer);
    }

    @Test
    void resultPrimaryFromMultiPathOperationIsNotMislabelledAsDeclaredTarget() {
        var memory = new SessionMemory();
        var supplied = new TurnRecord.ToolCallSummary("talos.move_path", "effect.md", List.of(), false, "",
                new ToolMutationEffects(List.of("effect.md"), List.of("uncertain.md")), EXECUTED);
        listener(memory).onTurnComplete(result(1, targetAudit(List.of(supplied), ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed move result");
        var call = memory.workStatusEvidence().turns().getFirst().calls().getFirst();
        assertEquals("", call.declaredTarget());
        assertEquals(List.of("effect.md"), call.appliedTargets());
        assertEquals(List.of("uncertain.md"), call.uncertainTargets());
        assertEquals(1, call.omittedTargets());
        String answer = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, TARGET_WORKSPACE);
        assertFalse(answer.contains("declared `effect.md`"), answer);
        assertTrue(answer.contains("applied [`effect.md`] uncertain [`uncertain.md`]"), answer);
    }

    @Test
    void missingAndDifferentWorkspaceBindingsCannotAttributeTargetsToCurrentWorkspace() {
        var memory = new SessionMemory();
        var supplied = new TurnRecord.ToolCallSummary("talos.edit_file", "scoped-canary.md", List.of(), true, "",
                ToolMutationEffects.applied("scoped-canary.md"), EXECUTED);
        listener(memory).onTurnComplete(result(1, targetAudit(List.of(supplied), ContentRestrictions.UNRESTRICTED, "")), "Missing scope");
        assertFalse(memory.workStatusEvidence().toString().contains("scoped-canary"));
        listener(memory).onTurnComplete(result(2, targetAudit(List.of(supplied), ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Known scope");
        String other = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, java.nio.file.Path.of("other-status-workspace"));
        assertFalse(other.contains("scoped-canary"), other);
        assertTrue(other.contains("1 unbound turns, 1 different-workspace turns"), other);
        assertTrue(other.contains("2 workspace-omitted entries"), other);
        String absent = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory);
        assertFalse(absent.contains("scoped-canary"), absent);
        assertTrue(absent.contains("Current workspace binding is unavailable"), absent);
    }

    @Test
    void overlongTargetsUnknownOperationsAndDetailRowsRemainBounded() {
        var memory = new SessionMemory();
        String tooLong = "x".repeat(WorkStatusEvidence.MAX_TARGET_CHARS + 1);
        var calls = new java.util.ArrayList<TurnRecord.ToolCallSummary>();
        calls.add(new TurnRecord.ToolCallSummary("talos.edit_file", tooLong, List.of(), true, "",
                ToolMutationEffects.applied(tooLong), EXECUTED));
        calls.add(new TurnRecord.ToolCallSummary("unknown-private-operation", "unrecognized-target.md", List.of(),
                false, "", ToolMutationEffects.applied("unrecognized-target.md"), UNKNOWN));
        for (int index = 0; index < WorkStatusAnswerPolicy.MAX_OPERATION_DETAIL_ROWS + 1; index++)
            calls.add(new TurnRecord.ToolCallSummary("talos.edit_file", "file-" + index + ".md", List.of(),
                    true, "", ToolMutationEffects.none(), EXECUTED));
        listener(memory).onTurnComplete(result(1, targetAudit(calls, ContentRestrictions.UNRESTRICTED,
                JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Constructed bounds");
        var retained = memory.workStatusEvidence().turns().getFirst().calls();
        assertEquals(2, retained.getFirst().omittedTargets());
        assertEquals(0, retained.getFirst().retainedTargets());
        assertEquals(1, retained.getFirst().appliedEntries());
        assertEquals("", retained.get(1).canonicalToolName());
        assertEquals(2, retained.get(1).withheldTargets());
        assertEquals(1, retained.get(1).appliedEntries());
        String answer = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, TARGET_WORKSPACE);
        assertFalse((memory.workStatusEvidence() + answer).contains(tooLong));
        assertFalse((memory.workStatusEvidence() + answer).contains("unrecognized-target"));
        assertEquals(WorkStatusAnswerPolicy.MAX_OPERATION_DETAIL_ROWS,
                answer.lines().filter(line -> line.startsWith("Turn 1 talos.edit_file [")).count());
        assertTrue(answer.contains("1 output-omitted entries"), answer);
        assertFalse(answer.contains("`file-" + WorkStatusAnswerPolicy.MAX_OPERATION_DETAIL_ROWS + ".md`"), answer);
    }

    @Test
    void targetBudgetsDoNotReduceCallLimitAndOutputOmissionsAreDisclosed() {
        var paths = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "folder-" + index + "/" + "x".repeat(560) + ".txt").toList();
        var supplied = new TurnRecord.ToolCallSummary("talos.edit_file", "declared.md", List.of(), true, "",
                new ToolMutationEffects(paths, List.of("maybe.md")), EXECUTED);
        var memory = new SessionMemory();
        listener(memory).onTurnComplete(result(1, targetAudit(java.util.Collections.nCopies(801, supplied),
                ContentRestrictions.UNRESTRICTED, JsonSessionStore.sessionIdFor(TARGET_WORKSPACE))), "Bounded metadata");
        var turn = memory.workStatusEvidence().turns().getFirst();
        assertEquals(800, turn.calls().size());
        assertEquals(1, turn.omittedCalls());
        assertTrue(turn.calls().stream().allMatch(call -> call.appliedEntries() == 40 && call.uncertainEntries() == 1));
        assertTrue(turn.calls().stream().allMatch(call -> call.appliedTargets().size() <= WorkStatusEvidence.MAX_TARGETS_PER_LIST));
        int chars = turn.calls().stream().mapToInt(call -> call.declaredTarget().length()
                + call.appliedTargets().stream().mapToInt(String::length).sum()
                + call.uncertainTargets().stream().mapToInt(String::length).sum()).sum();
        assertTrue(chars <= WorkStatusEvidence.MAX_TARGET_CHARS_PER_TURN);
        assertTrue(turn.calls().stream().mapToInt(WorkStatusEvidence.Call::omittedTargets).sum() > 0);
        String answer = WorkStatusAnswerPolicy.render(TARGET_REQUEST, memory, TARGET_WORKSPACE);
        assertFalse(answer.contains("0 output-omitted entries"), answer);
        assertTrue(answer.contains("output-omitted entries"), answer);
        assertTrue(answer.length() < WorkStatusAnswerPolicy.MAX_OPERATION_DETAIL_CHARS + 2500, answer);
        assertTrue(answer.contains("Call entries omitted by the per-turn bound: 1"), answer);
    }

    private static TurnAudit targetAudit(List<TurnRecord.ToolCallSummary> calls, ContentRestrictions restrictions,
                                         String workspaceHash) {
        var trace = LocalTurnTrace.builder("target-status", "fixture", 1, "2026-10-06T00:00:00Z")
                .workspaceHash(workspaceHash).verification("READBACK_ONLY", "", List.of()).build();
        return new TurnAudit(calls, 0, 0, 0, TurnPolicyTrace.empty(), trace, restrictions, List.of(), WorkStatusReceipt.none());
    }

    @Test
    void typedEarlierDenialsStaySeparateFromLaterEffectsAndCurrentReadOnlyPolicy() {
        var memory = new SessionMemory();
        var listener = listener(memory);
        var causes = List.of(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                dev.talos.tools.ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                dev.talos.tools.ToolFailureReason.NONE);
        for (int index = 0; index < causes.size(); index++) {
            var blocked = new TurnRecord.ToolCallSummary("talos.edit_file", PRIVATE_PATH, List.of(PRIVATE_PATH),
                    false, "Permission policy denied everything", ToolMutationEffects.none(), BLOCKED, causes.get(index));
            listener.onTurnComplete(result(index + 1, audit(List.of(blocked), "NOT_RUN",
                    ContentRestrictions.UNRESTRICTED, WorkStatusReceipt.none())), "Earlier request");
        }
        listener.onTurnComplete(result(5, audit(List.of(call("talos.edit_file", true, EXECUTED,
                ToolMutationEffects.applied(PRIVATE_PATH))), "READBACK_ONLY",
                ContentRestrictions.UNRESTRICTED, WorkStatusReceipt.none())), "Later fresh request");
        var request = WorkStatusRequest.parse("Give me a short handoff. What was denied earlier, "
                + "what actually changed afterwards, and what remains unverified? Do not change files or run tests.");
        String answer = WorkStatusAnswerPolicy.render(request, memory);
        assertTrue(answer.contains("Turn 1: 1 user approval denial(s)"), answer);
        assertTrue(answer.contains("Turn 2: 1 permission policy denial(s)"), answer);
        assertTrue(answer.contains("Turn 3: 1 tool execution authority rejection(s)"), answer);
        assertTrue(answer.contains("Turn 4: 1 unsuccessful call(s) without a recorded typed cause"), answer);
        assertTrue(answer.contains("Turn 5 file effect entries: 1 applied, 0 uncertain"), answer);
        assertFalse(answer.contains("Turn 1: 1 permission policy denial(s)"), answer);
        assertTrue(answer.contains("READBACK_ONLY: 1 retained entries"), answer);
        assertTrue(answer.contains("Operation details remain unresolved"), answer);
        assertTrue(answer.contains("Counts do not fully answer"), answer);
        assertNoPrivatePayload(answer);
    }

    @Test
    void conciseRuntimeStatusRetainsFailedEffectsUnknownRecordsAndMissingHistory() {
        var memory = new SessionMemory();
        var fixtureCalls = List.of(call("talos.edit_file", false, EXECUTED,
                new ToolMutationEffects(List.of(PRIVATE_PATH), List.of("uncertain-sentinel.txt"))));
        for (int turn = 1; turn <= 201; turn++) listener(memory).onTurnComplete(
                result(turn, audit(turn == 201 ? fixtureCalls : List.of(), turn == 201 ? "FAILED" : "UNKNOWN",
                        ContentRestrictions.UNRESTRICTED, WorkStatusReceipt.none())), "Constructed prior request");
        var request = WorkStatusRequest.parse("Give me a handoff. What actually changed? What remains unverified?");
        String answer = WorkStatusAnswerPolicy.render(request, memory);
        assertTrue(answer.contains("Turn 201 file effect entries: 1 applied, 1 uncertain"), answer);
        assertTrue(answer.contains("FAILED: 1 retained entries"), answer);
        assertTrue(answer.contains("UNKNOWN: 199 retained entries"), answer);
        assertTrue(answer.contains("Applied entries from unsuccessful calls: 1"), answer);
        assertTrue(answer.contains("Earlier status records evicted: 1"), answer);
        assertTrue(answer.contains("Earlier failures are not erased"), answer);
        assertTrue(answer.contains("Operation details remain unresolved"), answer);
        assertNoPrivatePayload(answer);
    }

    private static final String REQUEST = "Give me a handoff. Is this tested and ready to ship? "
            + "What should happen next? Keep proposed work separate from anything actually done in this conversation.";
    private static final WorkStatusRequest PLAN = WorkStatusRequest.parse(REQUEST);
    private static final String PRIVATE_PATH = "private-evidence-sentinel.txt";
    private static final String PRIVATE_REASON = "PRIVATE_REASON_SENTINEL";
    private static final ContentRestrictions RESTRICTED = new ContentRestrictions(false, false, false, false);
    private static final String SKETCH = "The conversation discussed several features and their explanations. "
            + "The user requested a concise continuation of the discussion.";

    @Test
    void listenerProjectsDispositionAndEffectsIndependentlyOfReportedSuccess() {
        var memory = new SessionMemory();
        var listener = listener(memory);
        var calls = List.of(
                call("talos.read_file", true, EXECUTED, ToolMutationEffects.none()),
                call("talos.run_command", true, EXECUTED, ToolMutationEffects.none()),
                call("talos.run_command", true, UNKNOWN, ToolMutationEffects.none()),
                call("talos.run_command", false, BLOCKED, ToolMutationEffects.none()),
                call("talos.edit_file", false, EXECUTED,
                        new ToolMutationEffects(List.of(PRIVATE_PATH), List.of("uncertain-sentinel.txt"))),
                call("custom-private-operation", true, UNKNOWN, ToolMutationEffects.none()));
        listener.onTurnComplete(result(7, audit(calls, "FAILED", ContentRestrictions.UNRESTRICTED,
                WorkStatusReceipt.none())), "Constructed audit input");

        var evidence = memory.workStatusEvidence();
        assertEquals(1, evidence.turns().size());
        var turn = evidence.turns().getFirst();
        assertEquals(7, turn.turnNumber());
        assertEquals(WorkStatusEvidence.Verification.FAILED, turn.verification());
        assertEquals(List.of(INSPECTION, COMMAND, COMMAND, COMMAND, MUTATION, OTHER),
                turn.calls().stream().map(WorkStatusEvidence.Call::category).toList());
        assertEquals(List.of(EXECUTED, EXECUTED, UNKNOWN, BLOCKED, EXECUTED, UNKNOWN),
                turn.calls().stream().map(WorkStatusEvidence.Call::disposition).toList());
        var failedMutation = turn.calls().get(4);
        assertFalse(failedMutation.reportedSuccess());
        assertEquals(1, failedMutation.appliedEntries());
        assertEquals(1, failedMutation.uncertainEntries());
        String answer = WorkStatusAnswerPolicy.render(PLAN, memory);
        assertTrue(answer.contains("Command calls: 1 reached execution, 1 reported success, 1 blocked, 1 execution unknown"), answer);
        assertTrue(answer.contains("Mutation calls: 1 reached execution, 0 reported success"), answer);
        assertTrue(answer.contains("Recorded file effect entries: 1 applied, 1 uncertain"), answer);
        assertTrue(answer.contains("not that a subprocess launched or files changed"), answer);
        assertTrue(answer.contains("No fresh workspace-content inspection or tool execution was performed"), answer);
        assertFalse(answer.contains("No fresh inspection or execution was performed"), answer);
        assertNoPrivatePayload(evidence.toString() + answer);
        assertThrows(UnsupportedOperationException.class, () -> evidence.turns().clear());
        assertThrows(UnsupportedOperationException.class, () -> turn.calls().clear());
    }

    @Test
    void staticPassAndSuccessfulCommandDoNotCertifyTestingOrRelease() {
        var memory = new SessionMemory();
        var listener = listener(memory);
        listener.onTurnComplete(result(1, audit(List.of(
                call("talos.run_command", true, EXECUTED, ToolMutationEffects.none())),
                "FAILED", ContentRestrictions.UNRESTRICTED, WorkStatusReceipt.none())), "Earlier fixture");
        listener.onTurnComplete(result(2, audit(List.of(), "PASSED", ContentRestrictions.UNRESTRICTED,
                WorkStatusReceipt.none())), "Static replacement fixture");

        assertEquals(List.of(WorkStatusEvidence.Verification.FAILED, WorkStatusEvidence.Verification.PASSED),
                memory.workStatusEvidence().turns().stream().map(WorkStatusEvidence.Turn::verification).toList());
        String answer = WorkStatusAnswerPolicy.render(PLAN, memory);
        assertTrue(answer.contains("PASSED: 1 retained entries"), answer);
        assertTrue(answer.contains("FAILED: 1 retained entries"), answer);
        assertTrue(answer.contains("Testing: not established"), answer);
        assertTrue(answer.contains("Release acceptance: not established"), answer);
        assertTrue(answer.contains("A recorded PASS is not proof of current source"), answer);
        assertTrue(answer.contains("Recorded failures and uncertain effects are not erased by later success"), answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASSED", "FAILED", "UNAVAILABLE", "NOT_RUN", "READBACK_ONLY", "UNKNOWN", "", "PRIVATE_REASON_SENTINEL"})
    void onlyFiniteVerificationValuesSurviveProjection(String suppliedStatus) {
        var memory = new SessionMemory();
        listener(memory).onTurnComplete(result(1, audit(List.of(), suppliedStatus,
                ContentRestrictions.UNRESTRICTED, WorkStatusReceipt.none())), "Constructed verifier fixture");
        var expected = suppliedStatus.isBlank() || suppliedStatus.equals(PRIVATE_REASON)
                ? WorkStatusEvidence.Verification.UNKNOWN : WorkStatusEvidence.Verification.valueOf(suppliedStatus);
        assertEquals(expected, memory.workStatusEvidence().turns().getFirst().verification());
        assertNoPrivatePayload(WorkStatusAnswerPolicy.render(PLAN, memory));
    }

    @Test
    void privacyProjectionRetainsFiniteCountsButWithholdsPayloadAndVerificationDetail() {
        var memory = new SessionMemory();
        var conversation = new ConversationManager(memory, new TokenBudget());
        var listener = new MemoryUpdateListener(conversation, null, memory);
        var restricted = audit(List.of(
                call("talos.read_file", true, EXECUTED, ToolMutationEffects.none()),
                call("talos.edit_file", false, EXECUTED, ToolMutationEffects.applied(PRIVATE_PATH)),
                call("custom-private-operation", false, UNKNOWN, ToolMutationEffects.none())),
                "PASSED", RESTRICTED, WorkStatusReceipt.none());
        assertNoPrivatePayload(restricted.toString());
        assertEquals("", restricted.toolCalls().getFirst().pathHint());
        assertNotEquals(PRIVATE_PATH, restricted.toolCalls().get(1).pathHint());
        listener.onTurnComplete(result(1, restricted), "Constructed restricted fixture");

        var evidence = memory.workStatusEvidence();
        assertEquals(WorkStatusEvidence.Verification.UNKNOWN, evidence.turns().getFirst().verification());
        assertEquals(3, evidence.turns().getFirst().calls().size());
        assertEquals(1, evidence.turns().getFirst().calls().get(1).appliedEntries());
        assertEquals(ContentRestrictions.WITHHELD, memory.getTurns().getLast().content());
        assertNoPrivatePayload(evidence.toString() + WorkStatusAnswerPolicy.render(PLAN, memory)
                + memory.toolEvidence() + memory.get());
    }

    @Test
    void statusWindowEvictsAfterTwoHundredAuditsAndClearResetsItsCounters() {
        var memory = new SessionMemory();
        var listener = listener(memory);
        for (int turn = 1; turn <= 200; turn++) {
            listener.onTurnComplete(result(turn, TurnAudit.empty()), "Constructed turn " + turn);
        }
        var captured = memory.workStatusEvidence();
        assertEquals(200, captured.turns().size());
        assertEquals(0, captured.evictedTurns());
        listener.onTurnComplete(result(201, TurnAudit.empty()), "Constructed turn 201");
        var current = memory.workStatusEvidence();
        assertEquals(200, current.turns().size());
        assertEquals(1, current.evictedTurns());
        assertEquals(2, current.turns().getFirst().turnNumber());
        assertEquals(201, current.turns().getLast().turnNumber());
        assertEquals(1, captured.turns().getFirst().turnNumber());
        assertEquals(0, captured.evictedTurns());
        assertTrue(WorkStatusAnswerPolicy.render(PLAN, memory).contains("Earlier status records evicted: 1"));
        memory.clear();
        assertTrue(memory.workStatusEvidence().turns().isEmpty());
        assertEquals(0, memory.workStatusEvidence().evictedTurns());
        assertFalse(memory.workStatusEvidence().memoryPresent());
        assertTrue(memory.toolEvidence().isEmpty());
    }

    @Test
    void restoredProseChangesAndCustomToolEvidenceCannotRebuildStatusProof() {
        var memory = new SessionMemory();
        var historicalCall = call("talos.edit_file", true, EXECUTED, ToolMutationEffects.applied(PRIVATE_PATH));
        var restored = new TurnRecord(4, Instant.parse("2026-10-01T00:00:00Z"), 0,
                "Historical request", "Everything was tested and is ready to ship.", List.of(historicalCall),
                0, 0, 0, "", "ok");
        // The restore-facing memory APIs are populated directly from a constructed durable record.
        memory.update(restored.userInput(), restored.assistantText());
        memory.setChangeSummaryContext(ChangeSummaryContext.fromTurnRecords(List.of(restored)));
        memory.recordToolEvidence(4, List.of(historicalCall,
                new TurnRecord.ToolCallSummary("talos.run_command", PRIVATE_PATH, true)));
        assertTrue(memory.hasContent());
        assertTrue(memory.changeSummaryContext().hasRecordedChanges());
        assertEquals(2, memory.toolEvidence().size());
        assertTrue(memory.workStatusEvidence().turns().isEmpty());
        String answer = WorkStatusAnswerPolicy.render(PLAN, memory);
        assertTrue(answer.contains("Runtime status records are unavailable"), answer);
        assertTrue(answer.contains("zero count does not prove no earlier action occurred"), answer);
        assertTrue(answer.contains("Testing: not established"), answer);
        assertTrue(answer.contains("Release acceptance: not established"), answer);
        assertNoPrivatePayload(answer);

        listener(memory).onTurnComplete(result(5, TurnAudit.empty()), "Constructed prose-only turn");
        assertEquals(1, memory.workStatusEvidence().turns().size());
        assertTrue(memory.workStatusEvidence().turns().getFirst().calls().isEmpty());
        assertEquals(WorkStatusEvidence.Verification.UNKNOWN, memory.workStatusEvidence().turns().getFirst().verification());
        assertTrue(WorkStatusAnswerPolicy.render(PLAN, memory).contains("PASSED: 0 retained entries"));
    }

    @Test
    void perTurnProjectionIsBoundedWithoutClaimingOmittedCallsAreAbsent() {
        var memory = new SessionMemory();
        var calls = java.util.Collections.nCopies(801,
                call("talos.read_file", true, EXECUTED, ToolMutationEffects.none()));
        listener(memory).onTurnComplete(result(1, audit(calls, "NOT_RUN",
                ContentRestrictions.UNRESTRICTED, WorkStatusReceipt.none())), "Constructed bounded audit");
        var evidence = memory.workStatusEvidence();
        assertEquals(800, evidence.turns().getFirst().calls().size());
        assertEquals(1, evidence.turns().getFirst().omittedCalls());
        assertTrue(WorkStatusAnswerPolicy.render(PLAN, memory)
                .contains("Call entries omitted by the per-turn bound: 1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeReceiptDefersEligibleCompactionIncludingPrivacyProjectionAndInvalidBinding(boolean assist) {
        for (boolean restricted : List.of(false, true)) {
            for (boolean invalidBinding : List.of(false, true)) {
                var memory = new SessionMemory();
                var conversation = compactionEligibleConversation(memory, assist);
                var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(SKETCH,
                        ModelIdentitySnapshot::empty);
                try (var llm = fixture.client()) {
                    String answer = "Constructed deterministic status answer.";
                    var receipt = WorkStatusReceipt.assessed(PLAN, answer, true);
                    if (invalidBinding) receipt = new WorkStatusReceipt(true, "invalid", "invalid",
                            PLAN.coverage(), PLAN.roles(), false);
                    var audit = audit(List.of(), "NOT_RUN", restricted ? RESTRICTED : ContentRestrictions.UNRESTRICTED,
                            receipt);
                    assertTrue(audit.workStatusReceipt().active());
                    assertEquals(!invalidBinding, audit.workStatusReceipt().matches(REQUEST, answer));
                    if (restricted) assertFalse(audit.workStatusReceipt().matches(REQUEST, ContentRestrictions.WITHHELD));
                    var listener = new MemoryUpdateListener(conversation, llm, memory);
                    listener.setAssistMode(assist);
                    listener.onTurnComplete(new TurnResult(new Result.Ok(answer), null, 13, Duration.ZERO, audit), REQUEST);
                    assertTrue(fixture.requests().isEmpty(), "An active receipt must not fall through to compaction");
                    assertEquals(13, conversation.turnCount());
                    assertNull(conversation.sketch());
                    assertNull(conversation.pollCompactionEvent());
                    assertEquals("NEVER_ATTEMPTED", conversation.meter(assist).lastCompaction().status());
                    if (restricted) assertEquals(ContentRestrictions.WITHHELD, memory.getTurns().getLast().content());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameEligibleHistoryStillCompactsForAnOrdinaryTurn(boolean assist) {
        var memory = new SessionMemory();
        var conversation = compactionEligibleConversation(memory, assist);
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(SKETCH,
                ModelIdentitySnapshot::empty);
        try (var llm = fixture.client()) {
            var listener = new MemoryUpdateListener(conversation, llm, memory);
            listener.setAssistMode(assist);
            listener.onTurnComplete(new TurnResult(new Result.Ok("A short ordinary answer."), 13),
                    "Continue the discussion.");
            assertEquals(1, fixture.requests().size());
            assertEquals(SKETCH, conversation.sketch());
            assertTrue(conversation.turnCount() < 13);
            assertNotNull(conversation.pollCompactionEvent());
        }
    }

    @Test
    void missingSettlementMarkerDoesNotEnableCompactionForAStatusRequest() {
        var memory = new SessionMemory();
        var conversation = compactionEligibleConversation(memory, false);
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(SKETCH,
                ModelIdentitySnapshot::empty);
        try (var llm = fixture.client()) {
            var listener = new MemoryUpdateListener(conversation, llm, memory);
            listener.onTurnComplete(new TurnResult(new Result.Ok("Retained status without its marker."), 13), REQUEST);
            assertTrue(fixture.requests().isEmpty(), "Missing status markers must not enable fallback generation");
            assertNull(conversation.sketch());
        }
    }

    @Test
    void malformedReceiptMetadataIsWithheldButOwnershipRemainsFailClosed() {
        var malformed = new WorkStatusReceipt(true, PRIVATE_PATH, PRIVATE_REASON,
                WorkStatusRequest.Coverage.COVERED, PLAN.roles(), true);
        assertTrue(malformed.active());
        assertFalse(malformed.presentationComplete());
        assertFalse(malformed.matches(REQUEST, "Any answer"));
        assertNoPrivatePayload(malformed.toString());
        assertNoPrivatePayload(audit(List.of(), "NOT_RUN", RESTRICTED, malformed).toString());
    }

    private static ConversationManager compactionEligibleConversation(SessionMemory memory, boolean assist) {
        var conversation = new ConversationManager(memory, new TokenBudget(200));
        for (int turn = 0; turn < 12; turn++) {
            conversation.addTurn("Explain feature " + turn,
                    "This constructed conversation contains a detailed explanation of a feature. "
                            + "Its behavior and purpose are described here for the continuing discussion.");
        }
        var meter = conversation.meter(assist);
        assertTrue(meter.turnPairs() >= meter.compactionThresholdPairs());
        assertTrue(meter.historyTokensEstimate() > meter.historyBudgetTokens());
        return conversation;
    }

    private static MemoryUpdateListener listener(SessionMemory memory) {
        return new MemoryUpdateListener(new ConversationManager(memory, new TokenBudget()), null, memory);
    }

    private static TurnRecord.ToolCallSummary call(String name, boolean success,
                                                  TurnRecord.ToolCallDisposition disposition,
                                                  ToolMutationEffects effects) {
        return new TurnRecord.ToolCallSummary(name, PRIVATE_PATH, List.of(PRIVATE_PATH), success,
                PRIVATE_REASON, effects, disposition);
    }

    private static TurnAudit audit(List<TurnRecord.ToolCallSummary> calls, String verification,
                                   ContentRestrictions restrictions, WorkStatusReceipt receipt) {
        var trace = LocalTurnTrace.builder("constructed-status", "offline-fixture", 1, "2026-10-01T00:00:00Z")
                .verification(verification, "Constructed static replacement check " + PRIVATE_REASON,
                        List.of(PRIVATE_PATH))
                .build();
        return new TurnAudit(calls, 0, 0, 0, TurnPolicyTrace.empty(), trace, restrictions, List.of(), receipt);
    }

    private static TurnResult result(int turn, TurnAudit audit) {
        return new TurnResult(new Result.Ok("Constructed assistant prose claims everything was tested and ready to ship."),
                null, turn, Duration.ZERO, audit);
    }

    private static void assertNoPrivatePayload(String text) {
        assertFalse(text.contains(PRIVATE_PATH), text);
        assertFalse(text.contains(PRIVATE_REASON), text);
        assertFalse(text.contains("uncertain-sentinel.txt"), text);
        assertFalse(text.contains("custom-private-operation"), text);
    }
}
