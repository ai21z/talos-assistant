package dev.talos.runtime.toolcall;

import dev.talos.core.util.Sanitize;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyFinalizationEvidenceTest {
    @TempDir Path workspace;

    @BeforeEach void begin() {
        LocalTurnTraceCapture.begin("source-selection", "tests", 1, "2026-09-13T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", "Inspect sources");
    }

    @AfterEach void clear() {
        dev.talos.safety.ContentRestrictionScope.end();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void ambientRestrictionsMayTightenDestinationsWithoutChangingObservedEvidence() throws Exception {
        var contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var original = state.messages.getLast();
        dev.talos.safety.ContentRestrictionScope.begin();
        var restrictions = new dev.talos.safety.ContentRestrictions(true, false, false, false);
        dev.talos.safety.ContentRestrictionScope.restrict(restrictions);
        var selected = ReadOnlyEvidenceContext.select(state);
        assertEquals(restrictions, selected.carriers().getFirst().message().contentRestrictions());
        assertEquals(original, state.messages.getLast(), "Durable execution evidence is not rewritten");
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selected);
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("Observed.", prepared(selected));
        var bound = frozen.bind("Observed.", receipt);
        assertEquals(ReadOnlyFinalizationEvidence.Reason.BOUND, bound.reason());
        assertTrue(bound.available());
    }

    @Test void currentCarrierRestrictionsSurviveSuccessfulFinalizationBinding() throws Exception {
        var contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        for (int mask = 0; mask < 8; mask++) {
            var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                    Map.of("script.js", "before\n"));
            var restrictions = new dev.talos.safety.ContentRestrictions(true,
                    (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            int index = state.messages.size() - 1;
            state.messages.set(index, state.messages.get(index).withContentRestrictions(restrictions));
            var selected = ReadOnlyEvidenceContext.select(state);
            assertEquals(restrictions, selected.carriers().getFirst().message().contentRestrictions());
            var frozen = ReadOnlyFinalizationEvidence.freeze(state, selected);
            var prepared = prepared(selected);
            var request = new dev.talos.spi.types.ChatRequest("fixture", "fixture", "", "", List.of(),
                    java.time.Duration.ofSeconds(1), prepared, List.of());
            assertEquals(restrictions, request.contentRestrictions, "mask=" + mask);
            var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("Observed.", prepared);
            var bound = frozen.bind("Observed.", receipt);
            assertEquals(ReadOnlyFinalizationEvidence.Reason.BOUND, bound.reason(), "mask=" + mask);
            assertTrue(bound.available(), "mask=" + mask);
        }
    }

    @Test void tighteningDoesNotPermitPayloadProtocolOrModelPermissionChanges() throws Exception {
        var contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selected = ReadOnlyEvidenceContext.select(state);
        var carrier = selected.carriers().getFirst();
        var original = carrier.message();
        var denied = new dev.talos.safety.ContentRestrictions(false, false, false, false);
        for (var changed : List.of(
                new ChatMessage("assistant", original.content(), original.toolCalls(), original.toolCallId()),
                ChatMessage.toolResult(original.toolCallId(), original.content() + "altered"),
                ChatMessage.toolResult("different-call", original.content()),
                new ChatMessage(original.role(), original.content(), List.of(
                        new ChatMessage.NativeToolCall("extra", "talos.read_file", Map.of("path", "other.txt"))),
                        original.toolCallId()),
                original.withContentRestrictions(denied))) {
            var altered = new ReadOnlyEvidenceContext.Selection(List.of(new ReadOnlyEvidenceContext.Carrier(
                    carrier.index(), changed, carrier.call(), carrier.evidence(), carrier.target())),
                    true, List.of(), false, false);
            assertEquals(ReadOnlyFinalizationEvidence.Reason.COVERAGE_UNAVAILABLE,
                    ReadOnlyFinalizationEvidence.freeze(state, altered)
                            .bind("Observed.", ProviderResponseCapture.Receipt.none()).reason());
        }
    }

    @Test void projectedCarrierCannotRelaxBoundDestinationRestrictions() throws Exception {
        var contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        dev.talos.safety.ContentRestrictionScope.begin();
        dev.talos.safety.ContentRestrictionScope.restrict(
                new dev.talos.safety.ContentRestrictions(true, false, false, false));
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        dev.talos.safety.ContentRestrictionScope.end();
        var selected = ReadOnlyEvidenceContext.select(state);
        var carrier = selected.carriers().getFirst();
        var original = carrier.message();
        var relaxed = new ChatMessage(original.role(), original.content(), original.toolCalls(), original.toolCallId());
        var altered = new ReadOnlyEvidenceContext.Selection(List.of(new ReadOnlyEvidenceContext.Carrier(
                carrier.index(), relaxed, carrier.call(), carrier.evidence(), carrier.target())),
                true, List.of(), false, false);
        assertEquals(ReadOnlyFinalizationEvidence.Reason.COVERAGE_UNAVAILABLE,
                ReadOnlyFinalizationEvidence.freeze(state, altered)
                        .bind("Observed.", ProviderResponseCapture.Receipt.none()).reason());
    }

    private static TaskContract contract(String... paths) {
        return new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of(paths), Set.of(), "Read the requested sources and explain the selector mismatch. Do not edit.");
    }

    @Test void selectedReceiptBindsExactCandidateAndOwningTurn() throws Exception {
        TaskContract contract = contract("index.html", "script.js");
        var evidence = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract, "Diagnosis.",
                Map.of("index.html", "<button id=\"calculate\"></button>",
                        "script.js", "document.querySelector('#calc');\n"));
        assertTrue(evidence.available());
        assertEquals(ReadOnlyFinalizationEvidence.Reason.BOUND, evidence.reason());
        assertNotNull(evidence.requestMeaning());
        assertEquals(contract.originalUserRequest(), evidence.requestMeaning().request());
        assertTrue(evidence.matches("Diagnosis.", workspace, contract));
        assertFalse(evidence.matches("Different text.", workspace, contract));
        assertFalse(evidence.matches("Diagnosis.", workspace.resolve("other"), contract));
        assertFalse(evidence.matches("Diagnosis.", workspace, contract("other.html")));
        assertFalse(evidence.generationId().isBlank());
        assertEquals(64, evidence.candidateHash().length());
        assertThrows(UnsupportedOperationException.class, () -> evidence.sources().clear());
        LocalTurnTraceCapture.clear();
        assertFalse(evidence.matches("Diagnosis.", workspace, contract));
    }

    @Test void diskDriftCannotChangeTheSelectedObservedText() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "before\n");
        TaskContract contract = contract("script.js");
        var evidence = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract, "Observed.",
                Map.of("script.js", "before\n"));
        Files.writeString(workspace.resolve("script.js"), "after\n");
        Files.delete(workspace.resolve("script.js"));
        assertTrue(evidence.matches("Observed.", workspace, contract));
        assertEquals("before\n", evidence.sources().getFirst().text());
        assertEquals("script.js", evidence.sources().getFirst().path());
    }

    @Test void exactDeliveryReceiptCannotAcquireResponseOnlyFinalizationAuthority() throws Exception {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("Observed.", prepared(selection),
                dev.talos.spi.types.ProviderReadInputBinding.DELIVERY_TAG);
        assertTrue(receipt.readInputBinding().bound());
        assertFalse(frozen.bind("Observed.", receipt).available());
        assertEquals(ReadOnlyFinalizationEvidence.Reason.REQUEST_ASSOCIATION_UNAVAILABLE,
                frozen.bind("Observed.", receipt).reason());
    }

    @Test void sourceProjectionKeepsLiteralDelimitersBlankLinesAndNestedNumberedText() {
        String body = "1 | const text = '2 | embedded';\n2 | \n3 | 1 | content, not a new observation\n";
        assertEquals("const text = '2 | embedded';\n\n1 | content, not a new observation\n",
                ReadOnlyFinalizationEvidence.decodeNumberedBody(body));
    }

    @Test void malformedNumberingAndPartialRecordsAreUnavailable() {
        for (String body : List.of("", "plain text\n", "1 | partial", "2 | skipped start\n",
                "1 | first\n3 | gap\n", "01 | leading zero\n", "1 | one\n1 | repeated\n",
                "1 | one\n\n", "1 | one\r\n", "1 | one\n... (1 more lines)\n",
                "1 | output truncated\n")) {
            assertNull(ReadOnlyFinalizationEvidence.decodeNumberedBody(body), body);
        }
    }

    @Test void originalLineEndingsAndTerminalNewlineAreNotClaimedAsRawBytes() throws Exception {
        TaskContract contract = contract("script.js");
        for (String raw : List.of("first\r\nsecond\r\n", "first\nsecond\n", "first\nsecond")) {
            var evidence = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract, "Observed.",
                    Map.of("script.js", raw));
            assertTrue(evidence.available());
            assertEquals("first\nsecond\n", evidence.sources().getFirst().text());
            assertEquals(ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION,
                    evidence.sources().getFirst().projectionVersion());
        }
    }

    @Test void knownSanitizerTransformationPreservesHtmlAndUsesTheTransformedSource() throws Exception {
        TaskContract contract = contract("index.html");
        String raw = "\u001b[31m<button id=\"calculate\">\u0000Go</button>\u001b[0m\n";
        var evidence = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract, "Observed.",
                Map.of("index.html", raw));
        assertTrue(evidence.available());
        assertEquals("<button id=\"calculate\">Go</button>\n", evidence.sources().getFirst().text());
    }

    @Test void frozenReadsAreNotReplacedByLaterLedgerEntries() throws Exception {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        var call = new ToolCall("talos.read_file", Map.of("path", "script.js"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, call, "script.js",
                ToolResult.ok("1 | later\n"));
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("Observed.", prepared(selection));
        var evidence = frozen.bind("Observed.", receipt);
        assertTrue(evidence.available());
        assertEquals("before\n", evidence.sources().getFirst().text());
    }

    @Test void changedRequestCannotRebindOrReuseTheFrozenMeaning() throws Exception {
        TaskContract original = contract("index.html", "script.js");
        TaskContract changed = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                original.expectedTargets(), Set.of(),
                "Please read index.html and script.js. Explain why the Calculate button is broken. Do not edit.");
        TurnTaskContractCapture.set(original);
        var sources = Map.of(
                "index.html", "<!doctype html><button id=\"calculate\">Calculate</button><script src=\"script.js\"></script>\n",
                "script.js", "const button = document.querySelector('#calc');\nbutton.addEventListener('click', () => {});\n");
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, original, sources);
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        var meaning = frozen.requestMeaning();
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("{}", prepared(selection));
        var bound = frozen.bind("{}", receipt);

        assertSame(meaning, frozen.pending().requestMeaning());
        assertSame(meaning, bound.requestMeaning());
        assertTrue(bound.matches("{}", workspace, original));
        assertFalse(bound.matches("{}", workspace, changed));

        TurnTaskContractCapture.set(changed);
        var rejected = frozen.bind("{}", receipt);
        assertFalse(rejected.available());
        assertEquals(ReadOnlyFinalizationEvidence.Reason.REQUEST_ASSOCIATION_UNAVAILABLE, rejected.reason());
        assertTrue(rejected.proposalSelected());
        assertSame(meaning, rejected.requestMeaning());
        assertEquals(original.originalUserRequest(), rejected.requestMeaning().request());

        var changedState = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, changed, sources);
        var fresh = ReadOnlyFinalizationEvidence.freeze(changedState, ReadOnlyEvidenceContext.select(changedState));
        assertNotSame(meaning, fresh.requestMeaning());
        assertEquals(changed.originalUserRequest(), fresh.requestMeaning().request());
        assertNotEquals(meaning.identity(), fresh.requestMeaning().identity());
        assertNotEquals(meaning.requestSha256(), fresh.requestMeaning().requestSha256());
    }

    @Test void changedMissingAndExtraPreparedResultsCannotBindFrozenSources() throws Exception {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        var expected = prepared(selection).getFirst();
        for (List<ChatMessage> changed : List.of(
                List.of(ChatMessage.toolResult(expected.toolCallId(), expected.content() + "changed")),
                List.<ChatMessage>of(),
                List.of(expected, ChatMessage.toolResult("unexpected", "extra")))) {
            var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("Observed.", changed);
            var evidence = frozen.bind("Observed.", receipt);
            assertFalse(evidence.available());
            assertEquals(ReadOnlyFinalizationEvidence.Reason.REQUEST_ASSOCIATION_UNAVAILABLE, evidence.reason());
        }
    }

    @Test void detachedReceiptAndReplacedCandidateCannotGainSourceAssociation() throws Exception {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        assertFalse(frozen.bind("Observed.", ProviderResponseCapture.Receipt.none()).available());
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("Observed.", prepared(selection));
        state.finishWithCapturedAnswer("Observed.", receipt);
        state.readOnlyFinalizationEvidence = frozen.bind("Observed.", receipt);
        state.transformAnswer("Observed.", LocalTurnTraceCapture.AnswerTransformationReason.TOOL_LOOP_FINALIZATION);
        assertTrue(state.readOnlyFinalizationEvidence.available());
        state.transformAnswer("Replacement.", LocalTurnTraceCapture.AnswerTransformationReason.TOOL_LOOP_FINALIZATION);
        assertSame(ReadOnlyFinalizationEvidence.empty(), state.readOnlyFinalizationEvidence);
    }

    @Test void receiptTextMustMatchBeforeTheExplicitStripProjection() throws Exception {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        String providerText = " \nObserved.\n\t";
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt(providerText, prepared(selection));
        var selected = frozen.bind(providerText, receipt);
        assertTrue(selected.available());
        assertTrue(selected.matches("Observed.", workspace, contract));
        assertFalse(selected.matches(providerText, workspace, contract));
        assertFalse(frozen.bind("Observed.", receipt).available());
        assertFalse(frozen.bind("Replacement.", receipt).available());
    }

    @Test void restartedCaptureCannotReuseAnEarlierSelectionWithTheSameTraceLabels() throws Exception {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("Observed.", prepared(selection));
        var evidence = frozen.bind("Observed.", receipt);
        assertTrue(evidence.matches("Observed.", workspace, contract));
        ProviderResponseCapture.currentScope().close();
        assertFalse(evidence.matches("Observed.", workspace, contract));
        LocalTurnTraceCapture.clear();
        begin();
        TurnTaskContractCapture.set(contract);
        assertFalse(evidence.matches("Observed.", workspace, contract));
        assertFalse(frozen.bind("Observed.", receipt).available());
        var freshFrozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        assertFalse(freshFrozen.bind("Observed.", receipt).available());
    }

    @Test void supplementaryAnswerCharactersPassButLoneSurrogatesCannotCollideWithQuestionMarks() throws Exception {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "before\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        String supplementary = "Observed \uD83D\uDE80.";
        var supplementaryReceipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt(
                supplementary, prepared(selection));
        assertTrue(frozen.bind(supplementary, supplementaryReceipt).matches(supplementary, workspace, contract));

        var questionMarkReceipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt(
                "Observed ?.", prepared(selection));
        assertTrue(frozen.bind("Observed ?.", questionMarkReceipt).available());
        for (String malformed : List.of("Observed \uD800.", "Observed \uDC00.")) {
            assertFalse(frozen.bind(malformed, questionMarkReceipt).available());
            var malformedReceipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt(
                    malformed, prepared(selection));
            assertFalse(frozen.bind(malformed, malformedReceipt).available());
        }
    }

    @Test void formatterBindingRejectsAnEmbeddedMatchingBodyInsideAnotherEnvelope() {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("script.js", "source\n"));
        var call = new ToolCall("talos.read_file", Map.of("path", "script.js"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, call, "script.js", ToolResult.ok("1 | fresh\n"));
        int index = state.messages.size() - 1;
        state.messages.set(index, ChatMessage.toolResult("observed-read-1",
                "Different envelope containing 1 | fresh\n in its content"));
        state.readEvidence.bindToolResult(call, index, state.messages.get(index));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
    }

    @Test void importedOriginSurvivesWhileLocalSequenceAndFreshOriginAdvance() {
        TaskContract contract = contract("script.js");
        TurnTaskContractCapture.set(contract);
        var source = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract,
                Map.of("aaa.txt", "unrelated\n", "script.js", "source\n"));
        ReadEvidence original = source.readEvidence.latestReadFileForPath("script.js", true);
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        var nested = new LoopState("", List.of(), new ArrayList<>(source.messages), workspace, null, null, 10, 0);
        nested.seedReadOnlyEvidence(handoff);
        ReadEvidence imported = nested.readEvidence.latestReadFileForPath("script.js", true);
        assertNotNull(imported);
        assertEquals(original.originId(), imported.originId());
        assertEquals(original.originOrdinal(), imported.originOrdinal());
        assertNotEquals(original.sequence(), imported.sequence());
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(nested,
                new ToolCall("talos.read_file", Map.of("path", "script.js")), "script.js",
                ToolResult.ok("1 | fresh\n"));
        ReadEvidence fresh = nested.readEvidence.latestReadFileForPath("script.js", true);
        assertNotEquals(imported.originId(), fresh.originId());
        assertTrue(fresh.sequence() > imported.sequence());
    }

    @Test void identicalBodiesRemainDistinctExecutionsAndEmptySourcesRemainUnavailable() throws Exception {
        TaskContract contract = contract("first.js", "second.js");
        var evidence = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract, "Observed.",
                Map.of("first.js", "same\n", "second.js", "same\n"));
        assertTrue(evidence.available());
        assertEquals(evidence.sources().getFirst().bodyHash(), evidence.sources().getLast().bodyHash());
        assertNotEquals(evidence.sources().getFirst().observationId(), evidence.sources().getLast().observationId());
        var empty = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract("empty.js"), "Observed.",
                Map.of("empty.js", ""));
        assertFalse(empty.available());
    }

    private static List<ChatMessage> prepared(ReadOnlyEvidenceContext.Selection selection) {
        return selection.carriers().stream().map(carrier -> ChatMessage.toolResult(
                carrier.message().toolCallId(), Sanitize.sanitizeMessageContent(carrier.message().content()))
                .withContentRestrictions(carrier.message().contentRestrictions())).toList();
    }

    @Test void selectedProposalRouteSurvivesEveryCandidateInvalidation() throws Exception {
        TaskContract contract = contract("index.html", "script.js");
        TurnTaskContractCapture.set(contract);
        var state = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract, Map.of(
                "index.html", "<!doctype html><button id=\"calculate\">Calculate</button><script src=\"script.js\"></script>\n",
                "script.js", "const button = document.querySelector('#calc');\nbutton.addEventListener('click', () => {});\n"));
        var selection = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, selection);
        assertTrue(frozen.proposalSelected());
        var meaning = frozen.requestMeaning();
        assertNotNull(meaning);
        assertSame(meaning, frozen.pending().requestMeaning());
        var receipt = ReadOnlyFinalizationEvidenceTestSupport.selectedReceipt("{}", prepared(selection));
        var evidence = frozen.bind("{}", receipt);
        assertTrue(evidence.available());
        assertTrue(evidence.proposalSelected());
        assertSame(meaning, evidence.requestMeaning());
        List<Runnable> replacements = List.of(
                () -> state.finishWithAnswer("replacement"),
                () -> state.finishWithCapturedAnswer("replacement", receipt),
                () -> state.finishWithToolAnswer("replacement"),
                () -> state.finishWithOperatorAnswer("replacement"),
                () -> state.transformAnswer("replacement", LocalTurnTraceCapture.AnswerTransformationReason.TOOL_LOOP_FINALIZATION),
                () -> state.applyProviderResult(null),
                () -> state.setRuntimeNativeCalls("replacement", List.of(), LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE));
        for (Runnable replacement : replacements) {
            state.currentText = "{}";
            state.readOnlyFinalizationEvidence = evidence;
            replacement.run();
            assertTrue(state.readOnlyFinalizationEvidence.proposalSelected());
            assertFalse(state.readOnlyFinalizationEvidence.available());
            assertFalse(state.readOnlyFinalizationEvidence.matches("replacement", workspace, contract));
            assertTrue(state.readOnlyFinalizationEvidence.sources().isEmpty());
            assertSame(meaning, state.readOnlyFinalizationEvidence.requestMeaning());
            assertSame(meaning, state.readOnlyFinalizationEvidence.invalidate().requestMeaning());
        }
        var rejected = frozen.bind("{}", ProviderResponseCapture.Receipt.none());
        assertTrue(rejected.proposalSelected());
        assertFalse(rejected.available());
        assertSame(meaning, rejected.requestMeaning());
    }
}
