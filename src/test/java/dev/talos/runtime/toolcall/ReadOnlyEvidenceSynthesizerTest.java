package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.NativeReadOnlyAnswerProposal;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyEvidenceSynthesizerTest {
    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();
    @BeforeEach void trace() {
        LocalTurnTraceCapture.begin("read-synthesis", "tests", 1, "2026-09-09T00:00:00Z",
                "workspace", "auto", "llama_cpp", "fixture", "diagnose files");
    }
    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    private LoopState state(LlmClient client) throws Exception {
        clients.add(client);
        var seed = ReadOnlyEvidenceContextTest.state(workspace);
        var state = new LoopState("", List.of(), seed.messages, workspace,
                Context.builder(new Config()).llm(client).build(), null, 10, 0);
        ReadOnlyEvidenceContextTest.populate(state);
        state.iterations = 10;
        state.totalToolsInvoked = 4;
        return state;
    }
    private static ToolCallExecutionStage.IterationOutcome duplicate() {
        return new ToolCallExecutionStage.IterationOutcome(0, List.of(), 0, false, false, false, 0);
    }

    @Test void oneCapturedResponseUsesAllFourBodiesWithoutToolsAndRetainsTheCap() throws Exception {
        String answer = "index.html contains the Calculate button. script.js uses the wrong selector. "
                + "README.md names script.js, while scripts.js is the unrelated sentinel.";
        var recorded = ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(
                List.of(new LlmClient.StreamResult(answer, List.of())), 8192, false);
        var state = state(recorded.client());
        assertFalse(recorded.client().supportsJsonSchemaResponse());
        var before = List.copyOf(state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, state.readOnlySynthesisStatus);
        assertEquals(answer, state.currentText);
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(state));
        assertTrue(state.currentResponseReceipt.providerTerminalEligible());
        assertFalse(state.currentResponseReceipt.providerToolCallEligible());
        assertEquals(1, recorded.requests().size());
        var request = recorded.requests().getFirst();
        assertTrue(request.tools.isEmpty());
        assertEquals(ToolChoiceMode.NONE, request.controls.toolChoice());
        assertEquals(ResponseFormatMode.TEXT, request.controls.responseFormat());
        assertEquals("", request.controls.jsonSchema());
        assertTrue(request.controls.singleAttempt());
        assertEquals(4096, request.controls.maxOutputTokens());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, request.controls.toolAuthorityMode());
        int anchor = -1;
        for (int i = 0; i < request.messages.size(); i++) {
            var message = request.messages.get(i);
            if ("system".equals(message.role()) && message.content().startsWith("[CurrentTurnCapability]")) anchor = i;
            if ("tool".equals(message.role())) {
                assertTrue(anchor >= 0 && anchor < i);
                assertTrue(message.content().contains("observed contents of"));
                assertEquals(message.toolCallId(), request.messages.get(i - 1).toolCalls().getFirst().id());
            }
        }
        assertEquals(4, request.messages.stream().filter(message -> "tool".equals(message.role())).count());
        assertFalse(state.readOnlyFinalizationEvidence.proposalSelected());
        assertEquals(4, state.totalToolsInvoked);
        assertEquals(0, state.mutatingToolSuccesses);
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(1, recorded.requests().size(), "No reinvocation after a selected synthesis");
    }

    @Test void completeReadsTriggerImmediatelyBeforeTheCapWithoutARepeatedRead() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("The supplied files establish the selector mismatch.", List.of())), 8192);
        var state = state(recorded.client());
        state.iterations = 4;
        assertEquals(0, state.cushionFiresRedundantRead);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, state.readOnlySynthesisStatus);
        assertFalse(new ToolCallRepromptStage().hitIterationLimit(state));
        assertEquals(1, recorded.requests().size());
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(1, recorded.requests().size(), "Immediate synthesis remains one-use");
    }

    @Test void missingOrInvalidatedEvidenceDoesNotDispatchSynthesis() throws Exception {
        for (boolean eligible : List.of(false, true)) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("Do not consume.", List.of())), 8192);
            var state = eligible ? eligibleState(recorded.client()) : state(recorded.client());
            ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
            var before = List.copyOf(state.messages);
            assertTrue(ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).isEmpty());
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, state.readOnlySynthesisStatus);
            assertTrue(recorded.requests().isEmpty());
            assertEquals(before, state.messages);
            assertEquals(4, state.totalToolsInvoked);
            assertEquals(0, state.mutatingToolSuccesses);
        }
    }

    @Test void immediateDeflectionAndToolPayloadAreRejectedWithoutASecondSynthesis() throws Exception {
        for (String answer : List.of("How can I help?", "{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"README.md\"}}")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult(answer, List.of())), 8192);
            var state = state(recorded.client());
            state.iterations = 4;
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
            assertTrue(state.failureDecision.shouldStop());
            assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(1, recorded.requests().size());
            assertFalse(new ToolCallRepromptStage().hitIterationLimit(state));
        }
    }

    @Test void nativeProposalCannotAcquireAuthorityDuringSynthesis() throws Exception {
        for (boolean eligible : List.of(false, true)) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                            "unexpected", "talos.read_file", Map.of("path", "README.md"))))), 8192);
            var state = eligible ? eligibleState(recorded.client()) : state(recorded.client());
            var before = List.copyOf(state.messages);
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
            assertTrue(state.currentNativeCalls.isEmpty());
            assertEquals(eligible, state.readOnlyFinalizationEvidence.proposalSelected());
            assertFalse(state.readOnlyFinalizationEvidence.available());
            assertEquals(4, state.totalToolsInvoked);
            assertEquals(0, state.mutatingToolSuccesses);
            assertEquals(before, state.messages);
            assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
            assertEquals(1, recorded.requests().size());
            var request = recorded.requests().getFirst();
            assertTrue(request.tools.isEmpty());
            assertEquals(ToolChoiceMode.NONE, request.controls.toolChoice());
            assertEquals(eligible ? ResponseFormatMode.JSON_SCHEMA : ResponseFormatMode.TEXT,
                    request.controls.responseFormat());
            assertTrue(request.controls.singleAttempt());
        }
    }

    @Test void closingOffersAndQuotedPhrasesAreNotLexicalDeflections() throws Exception {
        for (String answer : List.of(
                "index.html defines #calculate, but script.js uses #calc. README.md identifies script.js, "
                        + "while scripts.js is unrelated. Let me know if you want more detail.",
                "The diagnosis concerns a selector mismatch, not the conversational phrase 'how can I help'.")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult(answer, List.of())), 8192);
            var state = state(recorded.client());
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, state.readOnlySynthesisStatus);
            assertEquals(answer, state.currentText);
            assertTrue(state.currentResponseReceipt.providerTerminalEligible());
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(1, recorded.requests().size());
        }
    }

    @Test void leadingDeflectionsRemainRejectedDespiteWhitespaceAndCase() throws Exception {
        for (String answer : List.of("  \nHOW CAN I HELP?", "\tLet Me Know If You need anything.")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult(answer, List.of())), 8192);
            var state = state(recorded.client());
            assertTerminalRejectionWithoutReinvocation(state);
            assertEquals(1, recorded.requests().size());
        }
    }

    @Test void lengthAndUnrecognizedProviderStopsCannotBecomeAcceptedSynthesis() throws Exception {
        for (String finishReason : List.of("length", "unknown-provider-stop")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                    new LlmClient.StreamResult("Partial diagnosis from the observed files.", List.of(), finishReason)),
                    8192);
            var state = state(recorded.client());
            assertTerminalRejectionWithoutReinvocation(state);
            assertEquals(1, recorded.requests().size(), finishReason + " must not trigger another request");
            assertTrue(recorded.requests().getFirst().controls.singleAttempt());
            assertEquals(4096, recorded.requests().getFirst().controls.maxOutputTokens());
        }
    }

    @Test void rejectedGenerationRetainsOriginalSourceIdentitiesWithoutGrantingAnswerAuthority() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("", List.of(), "length")), 8192);
        var state = eligibleState(recorded.client());
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, ReadOnlyEvidenceContext.select(state));
        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertEquals(1, recorded.requests().size());
        assertFalse(state.readOnlyFinalizationEvidence.available());
        assertTrue(state.readOnlyFinalizationEvidence.sources().isEmpty());
        var trace = LocalTurnTraceCapture.complete();
        var events = trace.events().stream()
                .filter(event -> "READ_ONLY_SYNTHESIS_SOURCES".equals(event.type())).toList();
        assertEquals(1, events.size());
        assertEquals("DIAGNOSTIC_ONLY", events.getFirst().data().get("scope"));
        assertEquals(frozen.requestMeaning().identity(), events.getFirst().data().get("meaningId"));
        var sources = (List<?>) events.getFirst().data().get("sources");
        assertEquals(frozen.sources().stream().map(ReadOnlyFinalizationEvidence.Source::observationId).toList(),
                sources.stream().map(source -> ((Map<?, ?>) source).get("observationId")).toList());
        assertEquals(frozen.sources().stream().map(ReadOnlyFinalizationEvidence.Source::bodyHash).toList(),
                sources.stream().map(source -> ((Map<?, ?>) source).get("bodyHash")).toList());
        assertTrue(trace.events().indexOf(events.getFirst()) < java.util.stream.IntStream.range(0, trace.events().size())
                .filter(index -> "GENERATION_STARTED".equals(trace.events().get(index).type())).findFirst().orElseThrow());
    }

    @Test void syntheticAnswerWithoutForegroundProviderReceiptCannotBecomeAcceptedSynthesis() throws Exception {
        var state = state(LlmClient.scripted(List.of("Plausible but synthetic diagnosis.", "Must not be consumed.")));
        assertTerminalRejectionWithoutReinvocation(state);
        var trace = LocalTurnTraceCapture.complete();
        assertEquals(1, trace.events().stream().filter(event -> "GENERATION_STARTED".equals(event.type())).count());
        assertFalse(trace.events().stream().anyMatch(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type())
                && "MODEL_PROVIDER".equals(event.data().get("origin"))));
    }

    @Test void transportFailureAfterPartialTextCannotBecomeAcceptedSynthesis() throws Exception {
        var state = state(ScriptedNativeLlmClient.transientAfterPartialOutput(
                "The supplied files suggest a selector mismatch, but the response is unfinished."));
        assertTerminalRejectionWithoutReinvocation(state);
        var trace = LocalTurnTraceCapture.complete();
        assertEquals(1, trace.events().stream().filter(event -> "GENERATION_STARTED".equals(event.type())).count());
        assertEquals(1, trace.events().stream().filter(event -> "GENERATION_ATTEMPT_STARTED".equals(event.type())).count());
        assertFalse(trace.events().stream().anyMatch(event -> "GENERATION_COMPLETED".equals(event.type())));
    }

    private static void assertTerminalRejectionWithoutReinvocation(LoopState state) {
        var before = List.copyOf(state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(TerminalReadOnlyStopAnswer.SYNTHESIS_UNAVAILABLE_REASON, state.failureDecision.reason());
        assertFalse(state.currentResponseReceipt.providerTerminalEligible());
        assertSame(ReadOnlyFinalizationEvidence.empty(), state.readOnlyFinalizationEvidence);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertEquals(4, state.totalToolsInvoked);
        assertEquals(0, state.mutatingToolSuccesses);
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(state));
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(before, state.messages);
    }

    @Test void contextBudgetFailureCannotSilentlyDropBodiesOrEnterCompactRetry() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Do not consume.", List.of())), 512);
        var state = state(recorded.client());
        ReadOnlyEvidenceContextTest.read(state, "README.md", "long evidence ".repeat(500), Map.of("path", "README.md"));
        var before = List.copyOf(state.messages);
        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertTrue(recorded.requests().isEmpty(), "Budget fails before provider dispatch rather than trimming required evidence");
        assertEquals(before, state.messages);
    }

    @Test void largerSynthesisReserveRejectsAnOtherwiseFittingRequestWithoutDroppingEvidence() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Must not be consumed.", List.of())), 8192);
        var state = eligibleState(recorded.client());
        ReadOnlyEvidenceContextTest.read(state, "README.md",
                ReadOnlyFinalizationEvidenceTestSupport.numbered("x".repeat(11_000) + "\n"),
                Map.of("path", "README.md"));
        var before = List.copyOf(state.messages);
        var selection = ReadOnlyEvidenceContext.select(state);
        assertTrue(selection.complete());
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        assertTrue(frozen.proposalSelected());
        var request = ReadOnlyEvidenceSynthesizer.requestMessages(state, selection, frozen);
        String schema = NativeReadOnlyAnswerProposal.evaluationSchema(frozen.requestMeaning(), frozen.sources());
        assertTrue(ReadOnlyEvidenceSynthesizer.requestFits(request, schema));

        var oldAllowance = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Synthetic boundary control.", List.of())), 8192);
        clients.add(oldAllowance.client());
        var controls = new dev.talos.spi.types.ChatRequestControls(ToolChoiceMode.NONE, "",
                ResponseFormatMode.JSON_SCHEMA, schema, List.of("bounded-read-only-synthesis"), null, 2048)
                .withSingleAttempt(true);
        oldAllowance.client().chatFull(request, List.of(), controls);
        assertEquals(1, oldAllowance.requests().size(), "The old allowance fits the same complete request");
        assertEquals(request, oldAllowance.requests().getFirst().messages);

        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertTrue(recorded.requests().isEmpty(), "The larger reserve fails before any provider call");
        assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
        assertFalse(state.readOnlyFinalizationEvidence.available());
        assertEquals(before, state.messages);
        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertTrue(recorded.requests().isEmpty());
    }

    @Test void cancellationPropagatesAndCannotReinvoke() throws Exception {
        CancellationException cancellation = new CancellationException("operator stopped synthesis");
        var state = state(LlmClient.scriptedFailure(cancellation));
        state.iterations = 4;
        var before = List.copyOf(state.messages);
        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate())));
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
    }

    @Test void requestBoundIncludesNativeArgumentsAndEscaping() {
        var call = new ChatMessage.NativeToolCall("id", "talos.read_file",
                Map.of("path", "README.md", "ignored", "\\\"".repeat(20_000)));
        assertFalse(ReadOnlyEvidenceSynthesizer.requestFits(List.of(
                ChatMessage.assistantWithToolCalls("", List.of(call)))));
        assertTrue(ReadOnlyEvidenceSynthesizer.requestFits(List.of(ChatMessage.user("small request"))));
    }

    @Test void requestBoundIncludesDynamicSchemaAndEscapedMessagesUnderTheSameHardCap() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Do not consume.", List.of())), 8192);
        var state = eligibleState(recorded.client());
        var selection = ReadOnlyEvidenceContext.select(state);
        var finalization = ReadOnlyFinalizationEvidence.freeze(state, selection);
        String schema = NativeReadOnlyAnswerProposal.evaluationSchema(
                finalization.requestMeaning(), finalization.sources());
        var request = ReadOnlyEvidenceSynthesizer.requestMessages(state, selection);

        assertEquals(32_000, ReadOnlyEvidenceSynthesizer.MAX_REQUEST_CHARS);
        assertTrue(ReadOnlyEvidenceSynthesizer.requestFits(request, schema),
                "The eligible four-source request and its dynamic schema must fit together");
        int wireOverhead = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(List.of(ChatMessage.user(""))).length();
        var nearLimit = List.of(ChatMessage.user("x".repeat(32_000 - 2_048 - wireOverhead - schema.length() / 2)));
        assertTrue(ReadOnlyEvidenceSynthesizer.requestFits(nearLimit));
        assertFalse(ReadOnlyEvidenceSynthesizer.requestFits(nearLimit, schema),
                "Schema characters cannot be omitted from the unchanged request cap");
        var plain = List.of(ChatMessage.user("x".repeat(16_000)));
        var escaped = List.of(ChatMessage.user("\"".repeat(16_000)));
        assertTrue(ReadOnlyEvidenceSynthesizer.requestFits(plain, schema));
        assertFalse(ReadOnlyEvidenceSynthesizer.requestFits(escaped, schema),
                "Wire escaping counts even when visible message length is unchanged");
        assertFalse(ReadOnlyEvidenceSynthesizer.requestFits(request, null));
        assertTrue(recorded.requests().isEmpty());
    }

    @Test void providerFailureReturnsTruthfulIncompleteResult() throws Exception {
        var state = state(LlmClient.scriptedFailure(new IllegalStateException("fixture failure")));
        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
        assertTrue(state.failureDecision.shouldStop());
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(state));
    }

    @Test void eligibleSourcesConstrainTheProposalSchemaAndBindTheSelectedText() throws Exception {
        String candidate = """
                {"version":3,"catalogId":"unresolved-fixture-catalog","targetId":"t0","results":[]}
                """;
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(candidate, List.of())), 8192);
        var state = eligibleState(recorded.client());
        assertTrue(recorded.client().supportsJsonSchemaResponse());
        var before = List.copyOf(state.messages);
        var contract = TurnTaskContractCapture.get();

        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());

        assertEquals(1, recorded.requests().size());
        var request = recorded.requests().getFirst();
        assertTrue(request.tools.isEmpty());
        assertEquals(ToolChoiceMode.NONE, request.controls.toolChoice());
        assertEquals(ResponseFormatMode.JSON_SCHEMA, request.controls.responseFormat());
        assertTrue(request.controls.singleAttempt());
        assertEquals(4096, request.controls.maxOutputTokens());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, request.controls.toolAuthorityMode());
        assertEquals(1, request.messages.stream().filter(message -> "user".equals(message.role())
                && contract.originalUserRequest().equals(message.content())).count());
        assertTrue(ReadOnlyEvidenceSynthesizer.requestFits(request.messages, request.controls.jsonSchema()));
        String instructions = request.messages.getFirst().content();
        assertTrue(instructions.startsWith("A bounded internal interpretation is being requested."));
        assertTrue(instructions.contains("One JSON object is required with version, catalogId, targetId, results and interpretationDecisions."));
        assertTrue(instructions.contains("version=9\n"));
        assertFalse(instructions.contains("version=8\n"));
        assertFalse(instructions.contains("Answer the original request from the supplied observed results."));
        assertTrue(instructions.contains("Missing or unsupported slots cannot be made complete by omission."));
        assertTrue(instructions.contains("results is an object keyed by every required slot ID, exactly once."));
        assertTrue(instructions.contains("For DIAGNOSIS, SELECTOR and SCRIPT_DISTINCTION, evaluation is required"));
        assertTrue(instructions.contains("They do not establish evidence support, target uniqueness or completion."));
        assertFalse(instructions.contains("For a supported noncalculation"));
        assertTrue(instructions.contains("""
                Calculation roles are SOURCE_DEFINITION, IMPLEMENTATION and REQUEST_EXPRESSION.
                For a calculation, the value is either a selection object with formulaId, bindingIds and precisionId,
                or the string "unsupported" when no suitable selection is available.
                In a selection object, formulaId selects a formula of the required role and source, and bindingIds is an array containing one binding ID per formula variable.
                """));
        assertFalse(instructions.contains("For a calculation, a formula of the required role and source is selected"));
        assertTrue(instructions.contains("Formula relevance remains interpretation."));
        var evidence = state.readOnlyFinalizationEvidence;
        assertNotNull(evidence.requestMeaning());
        assertEquals(contract.originalUserRequest(), evidence.requestMeaning().request());
        assertEquals(NativeReadOnlyAnswerProposal.evaluationSchema(evidence.requestMeaning(), evidence.sources()), request.controls.jsonSchema());
        assertNotEquals(NativeReadOnlyAnswerProposal.decisionSchema(evidence.requestMeaning(), evidence.sources()), request.controls.jsonSchema());
        assertTrue(instructions.endsWith(NativeReadOnlyAnswerProposal.evaluationInstructions(evidence.requestMeaning(), evidence.sources())));
        assertEquals(List.of("script.js", "README.md", "index.html", "scripts.js"),
                evidence.sources().stream().map(ReadOnlyFinalizationEvidence.Source::path).toList());
        var toolResults = request.messages.stream().filter(message -> "tool".equals(message.role())).toList();
        assertEquals(4, toolResults.size());
        int priorCatalogPosition = -1;
        for (int index = 0; index < evidence.sources().size(); index++) {
            var source = evidence.sources().get(index);
            String catalogEntry = "s" + index + " path=\"" + source.path() + "\"\n";
            int position = instructions.indexOf(catalogEntry);
            assertTrue(position > priorCatalogPosition, catalogEntry);
            assertEquals(position, instructions.lastIndexOf(catalogEntry));
            priorCatalogPosition = position;
            assertTrue(toolResults.get(index).content().contains(ReadOnlyFinalizationEvidenceTestSupport.numbered(source.text())));
            assertFalse(request.messages.stream().filter(message -> "system".equals(message.role()))
                    .anyMatch(message -> message.content().contains(source.text())), source.path());
        }
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, state.readOnlySynthesisStatus);
        assertEquals(candidate.strip(), state.currentText);
        assertTrue(evidence.proposalSelected());
        assertTrue(evidence.available(), evidence.reason().name());
        assertTrue(evidence.matches(candidate.strip(), workspace, contract));
        assertFalse(evidence.matches(candidate.strip() + " changed", workspace, contract));
        assertEquals(state.currentResponseReceipt.generationId(), evidence.generationId());
        assertTrue(state.currentResponseReceipt.readInputBinding().bound());
        assertEquals(4, state.currentResponseReceipt.readInputBinding().preparedToolResults().size());
        assertEquals(4, state.totalToolsInvoked);
        assertEquals(0, state.mutatingToolSuccesses);
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(1, recorded.requests().size());
        var trace = LocalTurnTraceCapture.complete();
        var meaningEvents = trace.events().stream()
                .filter(event -> "READ_ONLY_REQUEST_MEANING".equals(event.type())).toList();
        assertEquals(1, meaningEvents.size());
        assertEquals("BOUNDED_REQUEST_RULES", meaningEvents.getFirst().data().get("origin"));
        assertEquals(evidence.requestMeaning().identity(), meaningEvents.getFirst().data().get("meaningId"));
        assertEquals(evidence.requestMeaning().requestSha256(), meaningEvents.getFirst().data().get("requestHash"));
    }

    @Test void unsupportedSchemaCapabilityRejectsEligibleSynthesisBeforeGenerationWithoutTextFallback() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(
                List.of(new LlmClient.StreamResult("Do not consume.", List.of())), 8192, false);
        var state = eligibleState(recorded.client());
        assertFalse(recorded.client().supportsJsonSchemaResponse());
        var before = List.copyOf(state.messages);

        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());

        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
        assertTrue(state.failureDecision.shouldStop());
        assertTrue(state.readOnlyFinalizationEvidence.proposalSelected());
        assertFalse(state.readOnlyFinalizationEvidence.available());
        assertTrue(state.readOnlyFinalizationEvidence.sources().isEmpty());
        assertFalse(state.currentResponseReceipt.providerTerminalEligible());
        assertTrue(state.currentNativeCalls.isEmpty());
        assertEquals(4, state.totalToolsInvoked);
        assertEquals(0, state.mutatingToolSuccesses);
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertTrue(recorded.requests().isEmpty());
        var trace = LocalTurnTraceCapture.complete();
        assertEquals(0, trace.events().stream().filter(event -> "GENERATION_STARTED".equals(event.type())).count());
    }

    @Test void aChangedRequestCannotReplaceTheFrozenMeaningInGenerationMessages() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Do not consume.", List.of())), 8192);
        var state = eligibleState(recorded.client());
        var selection = ReadOnlyEvidenceContext.select(state);
        var finalization = ReadOnlyFinalizationEvidence.freeze(state, selection);
        var original = TurnTaskContractCapture.get();
        var changed = new dev.talos.runtime.task.TaskContract(original.type(), false, false, false,
                original.expectedTargets(), original.sourceEvidenceTargets(),
                "Please read index.html and script.js. Explain why the Calculate button is broken. Do not edit.");
        TurnTaskContractCapture.set(changed);

        assertThrows(IllegalStateException.class,
                () -> ReadOnlyEvidenceSynthesizer.requestMessages(state, selection, finalization));
        assertEquals(original.originalUserRequest(), finalization.requestMeaning().request());
        assertTrue(recorded.requests().isEmpty());
    }

    @Test void rejectedEligibleSynthesisKeepsThePendingProposalRouteWithoutAnotherRequest() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("How can I help?", List.of())), 8192);
        var state = eligibleState(recorded.client());
        var before = List.copyOf(state.messages);
        var finalization = ReadOnlyFinalizationEvidence.freeze(state, ReadOnlyEvidenceContext.select(state));

        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());

        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
        assertTrue(state.failureDecision.shouldStop());
        assertTrue(state.readOnlyFinalizationEvidence.proposalSelected());
        assertFalse(state.readOnlyFinalizationEvidence.available());
        assertTrue(state.readOnlyFinalizationEvidence.sources().isEmpty());
        assertFalse(state.readOnlyFinalizationEvidence.matches(state.currentText, workspace, TurnTaskContractCapture.get()));
        assertFalse(state.currentResponseReceipt.providerTerminalEligible());
        assertTrue(state.currentNativeCalls.isEmpty());
        assertEquals(before, state.messages);
        assertEquals(1, recorded.requests().size());
        var request = recorded.requests().getFirst();
        assertTrue(request.tools.isEmpty());
        assertEquals(ToolChoiceMode.NONE, request.controls.toolChoice());
        assertEquals(ResponseFormatMode.JSON_SCHEMA, request.controls.responseFormat());
        assertEquals(NativeReadOnlyAnswerProposal.evaluationSchema(
                finalization.requestMeaning(), finalization.sources()), request.controls.jsonSchema());
        assertTrue(request.controls.singleAttempt());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, request.controls.toolAuthorityMode());
        assertTrue(recorded.requests().getFirst().messages.getFirst().content()
                .contains("version=9\n"));
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(1, recorded.requests().size());
        assertEquals(0, state.mutatingToolSuccesses);
    }

    private LoopState eligibleState(LlmClient client) throws Exception {
        var state = state(client);
        var sources = new java.util.LinkedHashMap<String, String>();
        sources.put("script.js", "const button = document.querySelector('#calc');\n"
                + "button.addEventListener('click', () => {});\n");
        sources.put("README.md", "# Retained source fixture\n\nThe Calculate button is declared in index.html.\n");
        sources.put("index.html", "<!doctype html><button id=\"calculate\">Calculate</button>"
                + "<script src=\"script.js\"></script>\n");
        sources.put("scripts.js", "const unrelatedSentinel = true;\n");
        for (var source : sources.entrySet()) {
            ReadOnlyEvidenceContextTest.read(state, source.getKey(),
                    ReadOnlyFinalizationEvidenceTestSupport.numbered(source.getValue()), Map.of("path", source.getKey()));
        }
        state.iterations = 4;
        assertTrue(ReadOnlyFinalizationEvidence.freeze(state, ReadOnlyEvidenceContext.select(state)).proposalSelected());
        return state;
    }
}
