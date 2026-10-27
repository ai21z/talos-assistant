package dev.talos.core.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderContextMembership;
import dev.talos.spi.types.ProviderTransportHandoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual ledger/capture APIs with controlled serialized provider-body fixtures. */
class ContextDeliveryCaptureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "runtime context source";
    private static final ChatMessage SOURCE_MESSAGE = ChatMessage.system(ORIGINAL);
    private static final List<ChatMessage> SOURCE_MESSAGES = List.of(SOURCE_MESSAGE);

    @AfterEach void clearOwner() {
        ContextLedgerCapture.clear();
    }

    @Test void runtimeBoundSourceProducesSeparateAdmissionAndSerializedDisposition() throws Exception {
        ContextItem source = beginBound("turn-one", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        var evidence = evidence(SOURCE_MESSAGES);
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            handoffOnWorker(request, evidence);
        }

        var snapshot = ContextLedgerCapture.complete();
        assertEquals(2, snapshot.entries().size());
        var admission = snapshot.entries().getFirst();
        assertEquals(ContextDecision.Action.ADMITTED_TO_CONTEXT, admission.decision().action());
        assertEquals("", admission.requestId());
        var disposition = deliveryEntries(snapshot).getFirst();
        assertSame(source, disposition.item());
        assertDisposition(disposition, ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF,
                "EXACT_MESSAGE_OBSERVED");
        assertEquals("context-request-1", disposition.requestId());
        assertEquals(evidence.providerBodyHash(), disposition.providerBodyHash());
        assertFalse(snapshot.toString().contains(ORIGINAL));
    }

    @Test void admissionAndUnboundUserQuotesCannotManufactureSourceDelivery() throws Exception {
        ContextLedgerCapture.begin("admission-only", 1);
        ContextItem source = source(ORIGINAL);
        ContextLedgerCapture.record(source, admission());
        var unbound = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        try (var request = unbound.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(evidence(SOURCE_MESSAGES));
        }
        assertTrue(deliveryEntries(ContextLedgerCapture.snapshot()).isEmpty());

        ContextLedgerCapture.bindPromptMessage("system", ORIGINAL, List.of(source));
        var userQuotes = List.of(ChatMessage.user(ORIGINAL));
        var bound = ContextLedgerCapture.deliveryCapture().prepare(userQuotes, userQuotes);
        try (var request = bound.beginRequest(userQuotes)) {
            request.handoff(evidence(userQuotes));
        }
        assertDisposition(deliveryEntries(ContextLedgerCapture.complete()).getFirst(),
                ContextDecision.Action.DELIVERY_UNOBSERVED, "SOURCE_REPRESENTATION_UNBOUND");
    }

    @Test void sanitizerRepresentationIsFollowedWithoutReplacingSourceIdentity() throws Exception {
        ContextItem source = beginBound("sanitized", 1, ORIGINAL);
        String sanitizedText = "runtime [redacted]";
        var sanitized = List.of(ChatMessage.system(sanitizedText));
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, sanitized);
        try (var request = prepared.beginRequest(sanitized)) {
            request.handoff(evidence(sanitized));
        }
        var entry = deliveryEntries(ContextLedgerCapture.complete()).getFirst();
        assertDisposition(entry, ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF,
                "EXACT_MESSAGE_OBSERVED");
        assertSame(source, entry.item());
        assertEquals(ProviderContextMembership.key("system", ORIGINAL).contentHash(), entry.item().textHash());
        assertNotEquals(ProviderContextMembership.key("system", sanitizedText).contentHash(), entry.item().textHash());
        assertEquals(ORIGINAL.length(), entry.item().chars());
    }

    @Test void fittedOmissionAndSerializedOmissionHaveDistinctReasons() throws Exception {
        beginBound("omissions", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        try (var request = prepared.beginRequest(List.of())) {
            request.handoff(evidence(List.of()));
        }
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(evidence(List.of()));
        }
        var entries = deliveryEntries(ContextLedgerCapture.complete());
        assertEquals(2, entries.size());
        assertDisposition(entries.get(0), ContextDecision.Action.WITHHELD_FROM_MODEL,
                "OMITTED_DURING_REQUEST_PREPARATION");
        assertDisposition(entries.get(1), ContextDecision.Action.WITHHELD_FROM_MODEL,
                "ABSENT_FROM_SERIALIZED_MESSAGES");
        assertNotEquals(entries.get(0).requestId(), entries.get(1).requestId());
    }

    @Test void actualSerializedRoleAndContentMustMatchThePreparedRepresentation() throws Exception {
        beginBound("changed-body", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        for (List<ChatMessage> changed : List.of(List.of(ChatMessage.user(ORIGINAL)),
                List.of(ChatMessage.system(ORIGINAL + " altered")))) {
            try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
                request.handoff(evidence(changed));
            }
        }
        var entries = deliveryEntries(ContextLedgerCapture.complete());
        assertEquals(2, entries.size());
        entries.forEach(entry -> assertDisposition(entry, ContextDecision.Action.WITHHELD_FROM_MODEL,
                "ABSENT_FROM_SERIALIZED_MESSAGES"));
    }

    @Test void actualSerializedPresenceWinsOverFittedOmission() throws Exception {
        beginBound("provider-disagrees-with-fit", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        try (var request = prepared.beginRequest(List.of())) {
            request.handoff(evidence(SOURCE_MESSAGES));
        }
        assertDisposition(deliveryEntries(ContextLedgerCapture.complete()).getFirst(),
                ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF, "SERIALIZED_OUTSIDE_PREPARED_MESSAGES");
    }

    @Test void conflictingCallbacksCannotRetainOrRestoreEarlierMembership() throws Exception {
        beginBound("conflicting-callbacks", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        var exact = evidence(SOURCE_MESSAGES);
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(exact);
            request.handoff(null);
            request.handoff(exact);
        }
        assertDisposition(deliveryEntries(ContextLedgerCapture.complete()).getFirst(),
                ContextDecision.Action.DELIVERY_UNOBSERVED, "CONFLICTING_HANDOFF_OBSERVATIONS");
    }

    @Test void unknownBodiesLegacyEvidenceAndAbsentCallbacksRemainUnobserved() {
        beginBound("unknown-body", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        for (String body : List.of("{}", "{\"messages\":[", "{\"messages\":[{\"role\":\"system\","
                + "\"content\":[{\"text\":\"runtime context source\"}]}]}")) {
            try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
                request.handoff(ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body, List.of()));
            }
        }
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(ProviderTransportHandoff.DispatchEvidence.unbound());
        }
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(null);
        }
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            // No callback is supplied by the legacy path.
        }
        var entries = deliveryEntries(ContextLedgerCapture.complete());
        assertEquals(6, entries.size());
        entries.subList(0, 5).forEach(entry -> assertDisposition(entry,
                ContextDecision.Action.DELIVERY_UNOBSERVED, "SERIALIZED_MEMBERSHIP_UNOBSERVED"));
        assertDisposition(entries.get(5), ContextDecision.Action.DELIVERY_UNOBSERVED,
                "NO_TRANSPORT_HANDOFF_OBSERVED");
    }

    @Test void mismatchedBodyIdentityCannotBorrowAnotherBodiesMembership() throws Exception {
        beginBound("forged-body", 1, ORIGINAL);
        var exact = evidence(SOURCE_MESSAGES);
        var omitted = evidence(List.of());
        var forged = new ProviderTransportHandoff.DispatchEvidence(omitted.providerBodyHash(), List.of(),
                Optional.empty(), exact.readInputMembership(), exact.contextMembership());
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(forged);
        }
        var entry = deliveryEntries(ContextLedgerCapture.complete()).getFirst();
        assertDisposition(entry, ContextDecision.Action.DELIVERY_UNOBSERVED,
                "SERIALIZED_MEMBERSHIP_UNOBSERVED");
        assertEquals(omitted.providerBodyHash(), entry.providerBodyHash());
    }

    @Test void repeatedRequestsRetainIndependentDispositionAndBodyIdentity() throws Exception {
        ContextItem source = beginBound("retry-owner", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        var retained = evidence(SOURCE_MESSAGES);
        var omitted = evidence(List.of());
        try (var first = prepared.beginRequest(SOURCE_MESSAGES)) {
            first.handoff(retained);
        }
        try (var retry = prepared.beginRequest(SOURCE_MESSAGES)) {
            retry.handoff(omitted);
        }
        var entries = deliveryEntries(ContextLedgerCapture.complete());
        assertEquals(2, entries.size());
        assertEquals("context-request-1", entries.get(0).requestId());
        assertEquals("context-request-2", entries.get(1).requestId());
        assertSame(source, entries.get(0).item());
        assertSame(source, entries.get(1).item());
        assertDisposition(entries.get(0), ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF,
                "EXACT_MESSAGE_OBSERVED");
        assertDisposition(entries.get(1), ContextDecision.Action.WITHHELD_FROM_MODEL,
                "ABSENT_FROM_SERIALIZED_MESSAGES");
        assertEquals(retained.providerBodyHash(), entries.get(0).providerBodyHash());
        assertEquals(omitted.providerBodyHash(), entries.get(1).providerBodyHash());
    }

    @Test void ownerCompletionSettlesPendingRequestsAndLateWorkerCannotContaminateNewTurn() throws Exception {
        beginBound("old-turn", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        var late = prepared.beginRequest(SOURCE_MESSAGES);
        var oldSnapshot = ContextLedgerCapture.complete();
        var oldEntry = deliveryEntries(oldSnapshot).getFirst();
        assertDisposition(oldEntry, ContextDecision.Action.DELIVERY_UNOBSERVED, "REQUEST_NOT_SETTLED");

        beginBound("new-turn", 2, "new source");
        var currentBeforeLateCallback = ContextLedgerCapture.snapshot();
        var exact = evidence(SOURCE_MESSAGES);
        handoffOnWorker(late, exact);
        late.close();
        assertEquals(currentBeforeLateCallback, ContextLedgerCapture.snapshot());
        assertEquals(oldEntry, deliveryEntries(oldSnapshot).getFirst());
        assertEquals("new-turn", ContextLedgerCapture.snapshot().traceId());
        assertTrue(deliveryEntries(ContextLedgerCapture.snapshot()).isEmpty());
        try (var afterCompletion = prepared.beginRequest(SOURCE_MESSAGES)) {
            afterCompletion.handoff(exact);
        }
        assertEquals(currentBeforeLateCallback, ContextLedgerCapture.complete());
    }

    @Test void ownerCloseKeepsObservedHandoffWhileUnobservedPendingRequestsBecomeUnknown() throws Exception {
        beginBound("pending", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        var first = prepared.beginRequest(SOURCE_MESSAGES);
        var second = prepared.beginRequest(SOURCE_MESSAGES);
        var observed = prepared.beginRequest(SOURCE_MESSAGES);
        var exact = evidence(SOURCE_MESSAGES);
        handoffOnWorker(observed, exact);
        var completed = ContextLedgerCapture.complete();
        var entries = deliveryEntries(completed);
        assertEquals(3, entries.size());
        entries.subList(0, 2).forEach(entry -> {
            assertDisposition(entry, ContextDecision.Action.DELIVERY_UNOBSERVED, "REQUEST_NOT_SETTLED");
            assertEquals("", entry.providerBodyHash());
        });
        assertDisposition(entries.get(2), ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF,
                "EXACT_MESSAGE_OBSERVED");
        assertEquals(exact.providerBodyHash(), entries.get(2).providerBodyHash());
        assertNotEquals(entries.get(0).requestId(), entries.get(1).requestId());
        first.close();
        second.close();
        second.close();
        observed.close();
        assertEquals(completed, ContextLedgerCapture.snapshot());
    }

    @Test void unrelatedNullableMalformedAndOversizedMessagesDoNotThrowDiagnostics() throws Exception {
        beginBound("bad-unrelated-messages", 1, ORIGINAL);
        var messages = new ArrayList<ChatMessage>(Arrays.asList(SOURCE_MESSAGE, null,
                new ChatMessage(null, "content"), new ChatMessage("user", null),
                ChatMessage.user("\uD800"),
                ChatMessage.user("x".repeat(ProviderContextMembership.MAX_STRING_CHARS + 1))));
        var capture = ContextLedgerCapture.deliveryCapture();
        var prepared = assertDoesNotThrow(() -> capture.prepare(messages, messages));
        var request = assertDoesNotThrow(() -> prepared.beginRequest(messages));
        var exact = evidence(SOURCE_MESSAGES);
        assertDoesNotThrow(() -> request.handoff(exact));
        assertDoesNotThrow(request::close);
        var entry = deliveryEntries(ContextLedgerCapture.complete()).getFirst();
        assertDisposition(entry, ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF,
                "EXACT_MESSAGE_OBSERVED");
    }

    @Test void noOwnerCaptureAndChangedSanitizerCardinalityRemainBounded() throws Exception {
        ContextLedgerCapture.clear();
        var empty = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        try (var request = empty.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(evidence(SOURCE_MESSAGES));
        }
        assertTrue(ContextLedgerCapture.snapshot().entries().isEmpty());

        beginBound("changed-cardinality", 1, ORIGINAL);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, List.of());
        try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
            request.handoff(evidence(SOURCE_MESSAGES));
        }
        assertDisposition(deliveryEntries(ContextLedgerCapture.complete()).getFirst(),
                ContextDecision.Action.DELIVERY_UNOBSERVED, "SOURCE_REPRESENTATION_UNBOUND");
    }

    @Test void nullableMessageListsAreDiagnosedWithoutBreakingTheCaller() throws Exception {
        beginBound("nullable-lists", 1, ORIGINAL);
        var capture = ContextLedgerCapture.deliveryCapture();
        var absent = assertDoesNotThrow(() -> capture.prepare(null, null));
        var request = assertDoesNotThrow(() -> absent.beginRequest(null));
        request.handoff(evidence(SOURCE_MESSAGES));
        request.close();
        assertDisposition(deliveryEntries(ContextLedgerCapture.snapshot()).getFirst(),
                ContextDecision.Action.DELIVERY_UNOBSERVED, "SOURCE_REPRESENTATION_UNBOUND");
        var valid = capture.prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        try (var withoutFit = valid.beginRequest(null)) {
            withoutFit.handoff(evidence(SOURCE_MESSAGES));
        }
        assertDisposition(deliveryEntries(ContextLedgerCapture.complete()).get(1),
                ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF, "SERIALIZED_OUTSIDE_PREPARED_MESSAGES");
    }

    @Test void unsupportedRuntimeBindingsKeepExplicitUnknownSourceReceipts() throws Exception {
        ContextLedgerCapture.begin("unsupported-bound-source", 1);
        var unsupported = List.of(new ChatMessage(null, "content"), new ChatMessage("system", null),
                ChatMessage.system("\uD800"),
                ChatMessage.system("x".repeat(ProviderContextMembership.MAX_STRING_CHARS + 1)));
        var items = new ArrayList<ContextItem>();
        for (int i = 0; i < unsupported.size(); i++) {
            ContextItem item = source("diagnostic source " + i);
            items.add(item);
            ContextLedgerCapture.record(item, admission());
            ChatMessage message = unsupported.get(i);
            assertDoesNotThrow(() -> ContextLedgerCapture.bindPromptMessage(
                    message.role(), message.content(), List.of(item)));
        }
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(List.of(), List.of());
        try (var request = prepared.beginRequest(List.of())) {
            request.handoff(evidence(List.of()));
        }
        var entries = deliveryEntries(ContextLedgerCapture.complete());
        assertEquals(items.size(), entries.size());
        assertEquals(items, entries.stream().map(ContextLedger.Entry::item).toList());
        entries.forEach(entry -> assertDisposition(entry, ContextDecision.Action.DELIVERY_UNOBSERVED,
                "SOURCE_REPRESENTATION_UNBOUND"));
    }

    @Test void identicalRepresentedMessagesRetainEveryDistinctSourceOncePerRequest() throws Exception {
        ContextItem first = beginBound("shared-representation", 1, ORIGINAL);
        ContextItem second = ContextItem.fromText(ContextItemSource.SESSION_MEMORY, ExecutionBoundary.SESSION_MEMORY,
                ContextPrivacyClass.NORMAL, "session.txt", "second underlying source", 5);
        assertNotEquals(first, second);
        assertNotEquals(first.textHash(), second.textHash());
        ContextLedgerCapture.bindPromptMessage("system", ORIGINAL, List.of(first, second, second));
        ContextLedgerCapture.bindPromptMessage("system", ORIGINAL, List.of(second));
        ContextLedgerCapture.bindPromptMessage("system", ORIGINAL, List.of());
        ContextLedgerCapture.bindPromptMessage("system", ORIGINAL, null);
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(SOURCE_MESSAGES, SOURCE_MESSAGES);
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var request = prepared.beginRequest(SOURCE_MESSAGES)) {
                request.handoff(evidence(SOURCE_MESSAGES));
            }
        }
        var entries = deliveryEntries(ContextLedgerCapture.complete());
        assertEquals(4, entries.size());
        for (String requestId : List.of("context-request-1", "context-request-2")) {
            var perRequest = entries.stream().filter(entry -> requestId.equals(entry.requestId())).toList();
            assertEquals(List.of(first, second), perRequest.stream().map(ContextLedger.Entry::item).toList());
            perRequest.forEach(entry -> assertDisposition(entry,
                    ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF, "EXACT_MESSAGE_OBSERVED"));
        }
    }

    @Test void emptyRepresentedContentAndDuplicateInitialRegistrationProduceOneSourceRow() throws Exception {
        ContextLedgerCapture.begin("empty-representation", 1);
        ContextItem item = source("");
        ContextLedgerCapture.bindPromptMessage("system", "", List.of(item, item));
        ContextLedgerCapture.bindPromptMessage("system", "", List.of());
        var messages = List.of(ChatMessage.system(""));
        var prepared = ContextLedgerCapture.deliveryCapture().prepare(messages, messages);
        try (var request = prepared.beginRequest(messages)) {
            request.handoff(evidence(messages));
        }
        var entries = deliveryEntries(ContextLedgerCapture.complete());
        assertEquals(1, entries.size());
        assertSame(item, entries.getFirst().item());
        assertDisposition(entries.getFirst(), ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF,
                "EXACT_MESSAGE_OBSERVED");
    }

    private static void handoffOnWorker(ContextDeliveryCapture.Request request,
                                        ProviderTransportHandoff.DispatchEvidence evidence) throws Exception {
        var callback = new FutureTask<Void>(() -> {
            request.handoff(evidence);
            return null;
        });
        var worker = new Thread(callback);
        worker.start();
        callback.get(5, TimeUnit.SECONDS);
    }

    private static ContextItem beginBound(String traceId, int turn, String text) {
        ContextLedgerCapture.begin(traceId, turn);
        ContextItem item = source(text);
        ContextLedgerCapture.record(item, admission());
        ContextLedgerCapture.bindPromptMessage("system", text, List.of(item));
        return item;
    }

    private static ContextItem source(String text) {
        return ContextItem.fromText(ContextItemSource.PROJECT_MEMORY, ExecutionBoundary.LOCAL_WORKSPACE,
                ContextPrivacyClass.NORMAL, "memory.txt", text, 7);
    }

    private static ContextDecision admission() {
        return new ContextDecision(ContextDecision.Action.ADMITTED_TO_CONTEXT, "RUNTIME_ADMISSION");
    }

    private static ProviderTransportHandoff.DispatchEvidence evidence(List<ChatMessage> messages) throws Exception {
        var encoded = new ArrayList<Map<String, String>>();
        for (ChatMessage message : messages) {
            var fields = new LinkedHashMap<String, String>();
            fields.put("role", message.role());
            fields.put("content", message.content());
            encoded.add(fields);
        }
        return ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                JSON.writeValueAsString(Map.of("messages", encoded)), List.of());
    }

    private static List<ContextLedger.Entry> deliveryEntries(ContextLedgerSnapshot snapshot) {
        return snapshot.entries().stream().filter(entry -> !entry.requestId().isEmpty()).toList();
    }

    private static void assertDisposition(ContextLedger.Entry entry, ContextDecision.Action action, String reason) {
        assertEquals(action, entry.decision().action());
        assertEquals(reason, entry.decision().reasonCode());
    }
}
