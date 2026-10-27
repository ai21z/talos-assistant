package dev.talos.cli.modes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native read cases execute real tools and turn settlement with a scripted provider.
 * Statement and ledger cases exercise ExecutionOutcome with constructed runtime records.
 * No mutation execution or live model accuracy is established by the constructed records.
 */
class MutationClaimAttributionBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST = "The documented expectation was updated too. Read the current README.md, "
            + "settings.json and label.py. Do they now agree, and is the original suggested change still needed? "
            + "Don't change files or run anything.";
    private static final List<String> READ_TARGETS = List.of("README.md", "settings.json", "label.py");
    private static final Map<String, String> FILES = Map.of(
            "README.md", "# Moss Labels\n\nThis is a tiny offline label example. The expected display label is oak. "
                    + "The active label is stored in settings.json. label.py returns that configured label. "
                    + "No network service or background task is used.\n",
            "settings.json", "{\n  \"label\": \"oak\",\n  \"network\": false\n}\n",
            "label.py", "def display_label(config):\n    \"\"\"Return the configured display label.\"\"\"\n"
                    + "    return config[\"label\"]\n",
            "notes.md", "# Review notes\n\nReview owner: Mara.\nPackaging remains pending.\n"
                    + "No test execution is recorded in this note.\n");

    // Authenticated against the retained settlement and final generation event before embedding.
    // The tracked regression has no dependency on excluded reports, trace paths or local state.
    private static final String SAVED_ANSWER = "**Current state**\n\n"
            + "| File | Relevant content |\n|------|------------------|\n"
            + "| `README.md` | “The expected display label is **oak**.” |\n"
            + "| `settings.json` | `{ \"label\": \"oak\", \"network\": false }` |\n"
            + "| `label.py` | `return config[\"label\"]` |\n\n"
            + "**Do they agree?**  \n"
            + "Yes. The README says the expected label is *oak*, and `settings.json` already contains "
            + "`\"label\": \"oak\"`. `label.py` simply returns that value, so the code will output *oak*.\n\n"
            + "**Is the original suggested change still needed?**  \n"
            + "No. The earlier suggestion was to change the label in `settings.json` to match a previous README "
            + "that expected *cedar*. Since the README has been updated to expect *oak*, the configuration is "
            + "already correct and no further change is required.";

    @TempDir Path workspace;

    @AfterEach
    void clearTurnCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnTraceCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void exactSavedExternalUpdateRemainsReadOnlyAnsweredThroughNativeReads() throws Exception {
        assertEquals(729, SAVED_ANSWER.length());
        assertEquals("f99f9fb218126a32a744af303120928478dae1d7ceceb2264e02155360f75639",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(SAVED_ANSWER.getBytes(StandardCharsets.UTF_8))));
        nativeReadSettlement(SAVED_ANSWER, false);
    }

    @Test
    void externalReportDoesNotExemptUnsupportedSettingsEditThroughNativeReads() throws Exception {
        nativeReadSettlement("You updated README.md. I changed settings.json to oak. The files agree.", true);
    }

    @Test
    void explicitNoChangeRemainsReadOnlyAnsweredThroughNativeReads() throws Exception {
        nativeReadSettlement("I changed no files during this turn. README.md expects oak, settings.json contains oak, "
                + "and label.py returns that value. The earlier cedar proposal is no longer needed.", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Talos updated README.md to expect oak during this turn.",
            "README.md was changed by Talos to expect oak during this turn."
    })
    void explicitTalosClaimIsUnsupportedThroughNativeReads(String answer) throws Exception {
        nativeReadSettlement(answer, true);
    }

    private void nativeReadSettlement(String answer, boolean unsupported) throws Exception {
        for (var file : FILES.entrySet()) Files.writeString(workspace.resolve(file.getKey()), file.getValue());
        var memory = new SessionMemory();
        // This concise history is synthetic. Only SAVED_ANSWER is an exact retained provider response.
        memory.update("Read the files without changing them. Is the documented cedar label configured?",
                "README.md expects cedar, while settings.json contains oak. A settings change was suggested.");
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var specs = registry.descriptors().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema())).toList();
        var responses = new ArrayList<LlmClient.StreamResult>();
        for (String path : READ_TARGETS) {
            responses.add(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                    "claim-read-" + path, "talos.read_file", Map.of("path", path)))));
        }
        responses.add(new LlmClient.StreamResult(answer, List.of()));
        var config = new Config(null);
        var fixture = ScriptedNativeLlmClient.recordingWithContextWindow(config, responses, 8192);
        try (var client = fixture.client()) {
            var context = Context.builder(config).memory(memory).llm(client)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).nativeToolSpecs(specs)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 6))
                    .build();
            var processor = new TurnProcessor((raw, ws, ctx) -> new UnifiedAssistantMode().handle(raw, ws, (Context) ctx));
            var settled = processor.process(new Session(workspace, config), REQUEST, context);
            String text = switch (settled.result()) {
                case Result.Ok ok -> ok.text;
                case Result.Streamed streamed -> streamed.fullText;
                default -> throw new AssertionError("Unexpected result " + settled.result());
            };
            assertEquals(4, fixture.requests().size());
            assertEquals(3, settled.audit().toolCalls().size());
            assertEquals(READ_TARGETS,
                    settled.audit().toolCalls().stream().map(TurnRecord.ToolCallSummary::pathHint).toList());
            for (var call : JSON.valueToTree(settled.audit()).path("toolCalls")) {
                assertEquals("talos.read_file", call.path("name").asText());
                assertTrue(call.path("success").asBoolean());
                assertEquals("EXECUTED", call.path("executionDisposition").asText());
                assertTrue(call.path("mutationEffects").path("appliedPaths").isEmpty());
                assertTrue(call.path("mutationEffects").path("uncertainPaths").isEmpty());
            }
            var delivered = fixture.requests().getLast().messages.stream()
                    .filter(m -> "tool".equals(m.role())).toList();
            assertEquals(READ_TARGETS.stream().map(path -> "claim-read-" + path).toList(),
                    delivered.stream().map(ChatMessage::toolCallId).toList());
            for (int i = 0; i < delivered.size(); i++) {
                for (String line : FILES.get(READ_TARGETS.get(i)).split("\n")) {
                    if (!line.isBlank()) assertTrue(delivered.get(i).content().contains(line.strip()), line);
                }
            }
            for (var file : FILES.entrySet()) {
                assertEquals(file.getValue(), Files.readString(workspace.resolve(file.getKey())), file.getKey());
            }
            assertEquals("NOT_RUN", settled.audit().localTrace().outcome().verificationStatus());
            assertEquals(unsupported ? "ADVISORY_ONLY" : "READ_ONLY_ANSWERED",
                    settled.audit().localTrace().outcome().classification(), text);
            assertFalse(text.contains("No file on disk was actually modified."), text);
            assertTrue(text.contains(answer), text);
            if (!unsupported) assertFalse(text.contains("[Truth check:"), text);
        }
    }

    static Stream<String> nonAssertions() {
        return Stream.of(
                "You updated README.md to expect oak. I only inspected it.",
                "README.md has been updated to expect oak, as you reported.",
                "The note says, ‘I updated README.md.’",
                "‘I updated README.md’ would be inaccurate. I only read it.",
                "Once README.md has been updated to expect oak, the files will agree.",
                "Proposed response, not completed work: ‘I updated README.md.’",
                "The example string is `I updated README.md`.",
                "I updated my assessment after reading README.md.",
                "The operator updated index.html. I only inspected it.",
                "Proposed response: ‘Here is the updated README.md.’",
                "The note has a heading called ‘Summary of changes’.",
                "README.md should be changed to oak, no edit was performed.",
                "I did not change README.md.",
                "I changed no files during this turn.");
    }

    @ParameterizedTest
    @MethodSource("nonAssertions")
    void nonAssertedOrExternalFileChangesDoNotBecomeFalseClaims(String answer) {
        var outcome = readOnlyOutcome(answer);
        assertFalse(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertEquals(answer, outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Earlier I updated README.md. This turn I only read it.",
            "README.md has been updated to expect oak."
    })
    void currentTurnLedgerDoesNotDisproveHistoricalOrUnattributedChanges(String answer) {
        var outcome = readOnlyOutcome(answer);
        // Current-turn absence cannot establish the historical actor or an unattributed edit's falsity.
        assertFalse(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("No file on disk was actually modified."));
        assertNotEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I updated README.md to expect oak during this turn.",
            "Talos updated README.md to expect oak during this turn.",
            "README.md was changed by Talos to expect oak during this turn.",
            "You updated README.md. I changed settings.json during this turn.",
            "No files were modified, but I created report.md during this turn."
    })
    void unsupportedCurrentTalosClaimsRemainAdvisory(String answer) {
        var outcome = readOnlyOutcome(answer);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertFalse(outcome.finalAnswer().contains("No file on disk was actually modified."), outcome.finalAnswer());
    }

    @Test
    void matchingCurrentTargetSupportsClaimWithConstructedLedger() {
        var outcome = mutationOutcome("I updated README.md during this turn.", List.of(edit("README.md")), 1, 0);
        assertFalse(outcome.falseMutationClaim(), outcome.finalAnswer());
    }

    @Test
    void unrelatedSuccessfulMutationDoesNotSupportClaimedTarget() {
        var outcome = mutationOutcome("I updated README.md during this turn.", List.of(edit("other.md")), 1, 0);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
    }

    @Test
    void matchingSuccessDoesNotExemptAnotherUnsupportedTargetInSameAnswer() {
        var outcome = mutationOutcome("I updated README.md and I changed settings.json during this turn.",
                List.of(edit("README.md")), 1, 0);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
    }

    @Test
    void scopedSuccessfulRecordRemainsSupportWhenAggregateSuccessIsZero() {
        var outcome = mutationOutcome("I updated README.md during this turn.", List.of(edit("README.md")), 0, 0);
        assertFalse(outcome.falseMutationClaim(), outcome.finalAnswer());
    }

    @Test
    void aggregateSuccessWithoutTargetRecordCannotCertifyClaim() {
        var outcome = mutationOutcome("I updated README.md during this turn.", List.of(), 1, 0);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
    }

    @Test
    void additionalAggregateSuccessWithoutTargetRecordCannotCertifyClaim() {
        var outcome = mutationOutcome("I updated README.md during this turn.", List.of(), 0, 1);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I updated README.md, settings.json.",
            "I updated README.md, settings.json and label.py.",
            "I updated README.md and settings.json."
    })
    void everyEnumeratedTargetRequiresItsOwnExecutionRecord(String answer) {
        var outcome = mutationOutcome(answer, List.of(edit("README.md")), 1, 0);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("settings.json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I updated README.md and did not change settings.json.",
            "I did not change settings.json, but I updated README.md.",
            "I updated README.md, but did not change settings.json."
    })
    void negatedNeighborDoesNotBecomeAnUnsupportedTarget(String answer) {
        var outcome = mutationOutcome(answer, List.of(edit("README.md")), 1, 0);
        assertFalse(outcome.falseMutationClaim(), outcome.finalAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"./README.md", "../README.md", "docs/release notes.md", "docs/release(notes).md", "docs/updated notes.md"})
    void completeTargetIdentityIsNotReducedToABasename(String path) {
        String answer = "I updated `" + path + "`.";
        var wrong = mutationOutcome(answer, List.of(edit("notes.md")), 1, 0);
        assertTrue(wrong.falseMutationClaim(), wrong.finalAnswer());
        var matching = mutationOutcome(answer, List.of(edit(path)), 1, 0);
        assertFalse(matching.falseMutationClaim(), matching.finalAnswer());
        assertFalse(matching.taskOutcome().hasWarning(
                dev.talos.runtime.outcome.TruthWarningType.UNRESOLVED_MUTATION_CLAIM));
    }

    @Test
    void unquotedPathWithSpacesCannotUseAnUnrelatedBasenameSuccess() {
        String answer = "I updated docs/release notes.md.";
        var wrong = mutationOutcome(answer, List.of(edit("notes.md")), 1, 0);
        assertTrue(wrong.falseMutationClaim(), wrong.finalAnswer());
        var matching = mutationOutcome(answer, List.of(edit("docs/release notes.md")), 1, 0);
        assertFalse(matching.falseMutationClaim(), matching.finalAnswer());
    }

    @Test
    void bareRootFilenameCannotBeBackedByItsSuffix() {
        String answer = "I updated release notes.md.";
        assertTrue(mutationOutcome(answer, List.of(edit("notes.md")), 1, 0).falseMutationClaim());
        assertFalse(mutationOutcome(answer, List.of(edit("release notes.md")), 1, 0).falseMutationClaim());
    }

    @Test
    void relativeAndAbsoluteTargetsAreBoundToTheCapturedWorkspace() {
        String answer = "I updated `./README.md`.";
        var loop = loop(answer, List.of(edit(workspace.resolve("README.md").toString())), 1);
        var selected = dev.talos.runtime.outcome.MutationFailureAnswerRenderer.selectMutationClaimAnswer(
                answer, loop, 0, workspace);
        assertFalse(selected.unsupportedCurrentClaim());
        assertEquals(answer, selected.answer());
    }

    @Test
    void fileCaseIsNotSilentlyConflatedByLexicalMatching() {
        String answer = "I updated Readme.md.";
        var outcome = mutationOutcome(answer, List.of(edit("README.md")), 1, 0);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
    }

    @Test
    void genericAllFilesClaimIsNotProvedByOneSuccess() {
        String answer = "I updated all files.";
        var selected = dev.talos.runtime.outcome.MutationFailureAnswerRenderer.selectMutationClaimAnswer(
                answer, loop(answer, List.of(edit("README.md")), 1), 0, null);
        assertTrue(selected.unresolvedClaim());
        assertFalse(selected.unsupportedCurrentClaim());
        assertTrue(selected.answer().startsWith(
                dev.talos.runtime.outcome.MutationFailureAnswerRenderer.UNVERIFIED_MUTATION_ANNOTATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"copy", "move"})
    void workspaceOperationSupportsDestinationDespiteSourcePathHint(String operation) {
        var record = workspaceOperation(operation);
        assertEquals(List.of("README.md"), record.workspaceOperationPlan().changedPaths());
        var outcome = mutationOutcome("I created README.md during this turn.", List.of(record), 0, 0);
        assertFalse(outcome.falseMutationClaim(), outcome.finalAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"copy", "move"})
    void workspaceOperationSourceHintDoesNotSupportFileContentEdit(String operation) {
        var outcome = mutationOutcome("I updated source.md during this turn.",
                List.of(workspaceOperation(operation)), 1, 0);
        assertTrue(outcome.falseMutationClaim(), outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
    }

    @ParameterizedTest
    @MethodSource("nonAssertions")
    void noToolSelectionDoesNotTurnNonAssertionsIntoExecutedMutationNarratives(String answer) {
        var plan = mutationPlan();
        var messages = List.of(ChatMessage.user(plan.taskContract().originalUserRequest()));
        assertFalse(NoToolAnswerTruthfulnessGuard.shouldReplaceStreamingNoToolMutationNarrative(answer, plan, messages),
                answer);
        var outcome = ExecutionOutcome.fromNoTool(answer, plan, messages, null, true);
        assertFalse(outcome.noToolMutationReplaced(), outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Talos updated README.md during this turn.",
            "README.md was changed by Talos during this turn.",
            "You updated README.md. I changed settings.json during this turn."
    })
    void noToolSelectionAndOutcomeRetainUnsupportedTalosClaims(String answer) {
        var plan = mutationPlan();
        var messages = List.of(ChatMessage.user(plan.taskContract().originalUserRequest()));
        assertTrue(NoToolAnswerTruthfulnessGuard.shouldReplaceStreamingNoToolMutationNarrative(answer, plan, messages),
                answer);
        var outcome = ExecutionOutcome.fromNoTool(answer, plan, messages, null, true);
        assertTrue(outcome.noToolMutationReplaced(), outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You updated README.md to expect oak. I only inspected it.",
            "README.md has been updated to expect oak, as you reported.",
            "Earlier I updated README.md. This turn I only read it.",
            "The note says, ‘I updated README.md.’",
            "Proposed response, not completed work: ‘I updated README.md.’",
            "Once README.md has been updated to expect oak, the files will agree.",
            "I updated my assessment."
    })
    void requiredMutationWithoutToolsStaysIncompleteRegardlessOfNarrative(String answer) {
        var plan = mutationPlan();
        var outcome = ExecutionOutcome.fromNoTool(answer, plan,
                List.of(ChatMessage.user(plan.taskContract().originalUserRequest())), null, true);
        assertTrue(outcome.mutationRequested());
        assertFalse(outcome.toolLoopRan());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
        assertNotEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
        assertNotEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You updated README.md. I only read it.",
            "README.md has been updated to expect oak.",
            "In the previous turn I updated README.md.",
            "The example says 'I updated README.md'."
    })
    void readOnlyToolExecutionCannotCompleteARequiredMutation(String answer) {
        var read = new ToolCallLoop.ToolOutcome("talos.read_file", "README.md", true, false, false, "read", "");
        var outcome = mutationOutcome(answer, List.of(read), 0, 0);
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertFalse(outcome.falseMutationClaim());
        assertTrue(outcome.taskOutcome().hasWarning(dev.talos.runtime.outcome.TruthWarningType.FAILED_ACTION_OBLIGATION));
    }

    private static ExecutionOutcome readOnlyOutcome(String answer) {
        String request = "Explain the supplied statement without changing anything.";
        var contract = new TaskContract(TaskType.READ_ONLY_QA, false, false, false, Set.of(), Set.of(), request);
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT, List.of(), List.of(), List.of());
        var read = new ToolCallLoop.ToolOutcome("talos.read_file", "README.md", true, false, false, "read", "");
        return ExecutionOutcome.fromToolLoop(answer, plan, List.of(ChatMessage.user(request)),
                loop(answer, List.of(read), 0), null, 0);
    }

    private static CurrentTurnPlan mutationPlan() {
        var contract = TaskContractResolver.fromUserRequest("Edit README.md to expect oak.");
        assertTrue(contract.mutationRequested());
        return CurrentTurnPlan.create(contract, ExecutionPhase.APPLY, List.of(), List.of(), List.of());
    }

    private static ExecutionOutcome mutationOutcome(String answer, List<ToolCallLoop.ToolOutcome> outcomes,
                                                    int aggregateSuccesses, int additionalSuccesses) {
        var plan = mutationPlan();
        return ExecutionOutcome.fromToolLoop(answer, plan,
                List.of(ChatMessage.user(plan.taskContract().originalUserRequest())),
                loop(answer, outcomes, aggregateSuccesses), null, additionalSuccesses);
    }

    private static ToolCallLoop.ToolOutcome edit(String path) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, true, true, false, "Edited " + path, "");
    }

    private static ToolCallLoop.ToolOutcome workspaceOperation(String operation) {
        var overwrite = WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS;
        var plan = operation.equals("copy")
                ? WorkspaceOperationPlan.copyPath("source.md", "README.md", overwrite, false)
                : WorkspaceOperationPlan.movePath("source.md", "README.md", overwrite);
        return new ToolCallLoop.ToolOutcome("talos." + operation + "_path", "source.md", true, true, false,
                "Operation applied", "", null, "", plan);
    }

    private static ToolCallLoop.LoopResult loop(String answer, List<ToolCallLoop.ToolOutcome> outcomes,
                                              int aggregateSuccesses) {
        return new ToolCallLoop.LoopResult(answer, 1, outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                0, 0, false, aggregateSuccesses, List.of(), 0, 0, 0, 0, outcomes);
    }
}
