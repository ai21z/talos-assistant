package dev.talos.spi.types;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderReadInputBindingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach void clear() { ProviderResponseCapture.clear(); }

    @Test void onlyTheSelectedOwnerReceiptExposesTheBoundInputs() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("read-1", "1 | source\n")));
        var generation = generation();
        var attempt = attempt(generation);
        attempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        String body = body(request.messages);
        completeTransport(attempt, ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body, List.of(), request));
        var receipt = attempt.respond(response("answer"));
        assertFalse(receipt.readInputBinding().bound());
        assertEquals("RESPONSE_NOT_SELECTED", receipt.readInputBinding().reasonCode());
        assertTrue(receipt.readInputBinding().preparedToolResults().isEmpty());

        var copied = new ProviderResponseCapture.Receipt(receipt.generationId(), receipt.attemptId(),
                receipt.attemptOrdinal(), receipt.source(), receipt.purpose(), receipt.completion(),
                true, false, receipt.assistantHash());
        assertThrows(IllegalArgumentException.class, () -> generation.complete(copied));
        assertEquals(ProviderReadInputBinding.Status.NOT_REQUESTED, copied.readInputBinding().status());
        generation.complete(receipt);

        var binding = receipt.readInputBinding();
        assertTrue(binding.bound());
        assertFalse(receipt.providerToolCallEligible());
        assertEquals(ProviderReadInputBinding.SANITIZER_VERSION, binding.sanitizerVersion());
        assertEquals(List.of(new ProviderReadInputBinding.ToolResultInput("read-1", "1 | source\n")),
                binding.preparedToolResults());
        assertEquals(ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body, List.of()).providerBodyHash(),
                binding.providerBodyHash());
        assertThrows(UnsupportedOperationException.class, () -> binding.preparedToolResults().clear());
    }

    @Test void exactDecodedEscapesAndDistinctResultIdentitiesAreRetained() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "1 | \"é\"\\\t\n"),
                ChatMessage.toolResult("two", "1 | \"é\"\\\t\n")));
        String json = body(request.messages).replace("é", "\\u00e9");
        var binding = selected(request, json).readInputBinding();
        assertTrue(binding.bound());
        assertEquals(List.of("one", "two"), binding.preparedToolResults().stream()
                .map(ProviderReadInputBinding.ToolResultInput::toolCallId).toList());
        assertEquals(request.messages.getFirst().content(), binding.preparedToolResults().getFirst().content());
    }

    @Test void changedMissingExtraOrReorderedSerializedResultsCannotBind() throws Exception {
        var original = List.of(ChatMessage.toolResult("one", "first"), ChatMessage.toolResult("two", "second"));
        var request = request(original);
        for (List<ChatMessage> altered : List.of(
                List.of(ChatMessage.toolResult("one", "changed"), original.getLast()),
                List.of(original.getFirst()),
                List.of(original.getFirst(), original.getLast(), ChatMessage.toolResult("three", "extra")),
                List.of(original.getLast(), original.getFirst()))) {
            var binding = selected(request, body(altered)).readInputBinding();
            assertFalse(binding.bound());
            assertEquals("TOOL_RESULT_MEMBERSHIP_MISMATCH", binding.reasonCode());
        }
    }

    @Test void malformedAmbiguousOrUnboundedJsonRemainsUnavailable() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "first")));
        for (String json : List.of(
                "{\"messages\":[],\"messages\":[]}",
                "{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"one\",\"content\":\"first\",\"content\":\"second\"}]}",
                "{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"one\",\"content\":[]}]}",
                "{\"messages\":null}", body(request.messages) + "{}",
                "{\"messages\":[],\"extra\":" + "[".repeat(ProviderReadInputBinding.MAX_SERIALIZED_NESTING_DEPTH)
                        + "0" + "]".repeat(ProviderReadInputBinding.MAX_SERIALIZED_NESTING_DEPTH) + "}")) {
            assertFalse(selected(request, json).readInputBinding().bound(), json);
        }
        String duplicate = body(List.of(request.messages.getFirst(), request.messages.getFirst()));
        assertEquals("DUPLICATE_SERIALIZED_TOOL_ID", selected(request, duplicate).readInputBinding().reasonCode());
    }

    @Test void acceptedSchemaDepthFitsTheWholeBodyAndTheNextDepthIsRejected() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "source")));
        String atBound = bodyWithSchema(request.messages, 32);
        assertEquals(ProviderReadInputBinding.MAX_SERIALIZED_NESTING_DEPTH, depth(JSON.readTree(atBound)));
        assertTrue(selected(request, atBound).readInputBinding().bound());
        String aboveBound = bodyWithSchema(request.messages, 33);
        assertEquals(ProviderReadInputBinding.MAX_SERIALIZED_NESTING_DEPTH + 1, depth(JSON.readTree(aboveBound)));
        assertEquals("INVALID_SERIALIZED_JSON", selected(request, aboveBound).readInputBinding().reasonCode());
    }

    @Test void duplicateNestedSchemaPropertiesAreRejectedBeforeMembershipCanBind() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "source")));
        String nested = bodyWithSchema(request.messages, 32);
        for (String duplicate : List.of(
                "{\"type\":\"object\",\"type\":\"string\"}",
                "{\"type\":\"object\",\"ty\\u0070e\":\"string\"}")) {
            String altered = nested.replace("{\"type\":\"object\"}", duplicate);
            assertNotEquals(nested, altered);
            assertEquals("INVALID_SERIALIZED_JSON", selected(request, altered).readInputBinding().reasonCode());
        }
    }

    @Test void nestedSchemasDoNotPermitChangedMissingExtraOrReorderedReadMembership() throws Exception {
        var original = List.of(ChatMessage.toolResult("one", "first"), ChatMessage.toolResult("two", "second"));
        var request = request(original);
        for (List<ChatMessage> altered : List.of(
                List.of(ChatMessage.toolResult("one", "changed"), original.getLast()),
                List.of(original.getFirst()),
                List.of(original.getFirst(), original.getLast(), ChatMessage.toolResult("three", "extra")),
                List.of(original.getLast(), original.getFirst()))) {
            var binding = selected(request, bodyWithSchema(altered, 32)).readInputBinding();
            assertFalse(binding.bound());
            assertEquals("TOOL_RESULT_MEMBERSHIP_MISMATCH", binding.reasonCode());
        }
    }

    @Test void serializedUtf8ByteLimitIsEnforcedAtTheExactBoundary() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "é")));
        String json = body(request.messages);
        int padding = ProviderReadInputBinding.MAX_SERIALIZED_BYTES - json.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(selected(request, json + " ".repeat(padding)).readInputBinding().bound());
        assertEquals("SERIALIZED_INPUT_LIMIT",
                selected(request, json + " ".repeat(padding + 1)).readInputBinding().reasonCode());
    }

    @Test void invalidUnicodeCannotCollapseDistinctInputsIntoTheSameUtf8Hash() throws Exception {
        var replacement = request(List.of(ChatMessage.toolResult("one", "?")));
        String loneSurrogate = String.valueOf((char) 0xD800);
        var malformed = request(List.of(ChatMessage.toolResult("one", loneSurrogate)));
        assertArrayEquals("?".getBytes(StandardCharsets.UTF_8), loneSurrogate.getBytes(StandardCharsets.UTF_8));
        assertEquals("INVALID_PREPARED_TOOL_RESULT",
                selected(malformed, body(replacement.messages)).readInputBinding().reasonCode());
        assertEquals("INVALID_SERIALIZED_TOOL_RESULT", selected(replacement,
                "{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"one\",\"content\":\"\\ud800\"}]}")
                .readInputBinding().reasonCode());
        var supplementary = request(List.of(ChatMessage.toolResult("one", "\uD83D\uDD0E")));
        assertTrue(selected(supplementary, body(supplementary.messages)).readInputBinding().bound());
    }

    @Test void preparedCountContentAndIdentityLimitsAreEnforced() throws Exception {
        var limit = request(List.of(ChatMessage.toolResult("one", "x".repeat(16_000))));
        assertTrue(selected(limit, body(limit.messages)).readInputBinding().bound());
        var oversized = request(List.of(ChatMessage.toolResult("one", "x".repeat(16_001))));
        assertEquals("PREPARED_INPUT_LIMIT", selected(oversized, body(oversized.messages)).readInputBinding().reasonCode());
        var eight = new ArrayList<ChatMessage>();
        for (int i = 0; i < 8; i++) eight.add(ChatMessage.toolResult("read-" + i, "source"));
        assertTrue(selected(request(eight), body(eight)).readInputBinding().bound());
        eight.add(ChatMessage.toolResult("ninth", "source"));
        assertEquals("PREPARED_INPUT_LIMIT", selected(request(eight), body(eight)).readInputBinding().reasonCode());
        var duplicate = request(List.of(ChatMessage.toolResult("same", "one"), ChatMessage.toolResult("same", "two")));
        assertEquals("DUPLICATE_PREPARED_TOOL_ID", selected(duplicate, body(duplicate.messages)).readInputBinding().reasonCode());
    }

    @Test void optInDoesNotGrantAuthorityOrReplaceActualMembership() throws Exception {
        var tagged = request(List.of(ChatMessage.toolResult("one", "source")));
        var untagged = new ChatRequest("local", "model", "", "", List.of(), null,
                tagged.messages, List.of());
        assertEquals(ProviderReadInputBinding.Status.NOT_REQUESTED,
                selected(untagged, body(untagged.messages)).readInputBinding().status());
        var toolRequest = new ChatRequest("local", "model", "", "", List.of(), null,
                tagged.messages, List.of(new ToolSpec("talos.read_file", "Read", "{}")), tagged.controls);
        assertEquals("REQUEST_NOT_RESPONSE_ONLY", selected(toolRequest, body(toolRequest.messages)).readInputBinding().reasonCode());

        var generation = generation();
        var attempt = attempt(generation);
        attempt.bindPreparedReadInputs(tagged, ProviderReadInputBinding.SANITIZER_VERSION);
        completeTransport(attempt, ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body(tagged.messages), List.of()));
        var receipt = attempt.respond(response("answer"));
        generation.complete(receipt);
        assertEquals("SERIALIZED_INPUT_NOT_CAPTURED", receipt.readInputBinding().reasonCode());
    }

    @Test void ordinaryDeliveryIsBoundWithoutGrantingToolAuthority() throws Exception {
        var messages = List.of(ChatMessage.toolResult("source-1", "1 | current source\n"));
        var request = deliveryRequest(messages);
        var receipt = selected(request, body(messages));
        assertTrue(receipt.readInputBinding().bound());
        assertEquals(ProviderReadInputBinding.Purpose.DELIVERY, receipt.readInputBinding().purpose());
        assertEquals(messages.getFirst().content(), receipt.readInputBinding().preparedToolResults().getFirst().content());
        assertFalse(receipt.providerToolCallEligible(), "Data membership cannot manufacture executable authority");
    }

    @Test void ordinaryDeliveryCannotBindAChangedSerializedSource() throws Exception {
        var request = deliveryRequest(List.of(ChatMessage.toolResult("source-1", "current source")));
        var receipt = selected(request, body(List.of(ChatMessage.toolResult("source-1", "stale source"))));
        assertFalse(receipt.readInputBinding().bound());
        assertEquals("TOOL_RESULT_MEMBERSHIP_MISMATCH", receipt.readInputBinding().reasonCode());
    }

    @Test void legacyUnobservedTransportCannotClaimDeliveryFromSerializedMembership() throws Exception {
        var request = deliveryRequest(List.of(ChatMessage.toolResult("source", "current source")));
        var generation = generation();
        var attempt = attempt(generation);
        attempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        assertTrue(attempt.tryRequestDispatched(ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                body(request.messages), request.tools, request)));
        var receipt = attempt.respond(response("answer"));
        generation.complete(receipt);
        assertTrue(receipt.selected());
        assertFalse(receipt.readInputBinding().bound());
        assertEquals("DELIVERY_TRANSPORT_NOT_OBSERVED", receipt.readInputBinding().reasonCode());
    }

    @Test void deliveryTagCannotRelaxAnExplicitResponseOnlyRequest() throws Exception {
        var base = deliveryRequest(List.of(ChatMessage.toolResult("source-1", "source")));
        var controls = new ChatRequestControls(ToolChoiceMode.AUTO, "", ResponseFormatMode.TEXT, "",
                List.of("bounded-read-evidence-delivery", ProviderReadInputBinding.REQUEST_TAG));
        var request = new ChatRequest("local", "model", "", "", List.of(), null,
                base.messages, base.tools, controls);
        assertEquals("REQUEST_NOT_RESPONSE_ONLY", selected(request, body(request.messages)).readInputBinding().reasonCode());
    }

    private static ChatRequest deliveryRequest(List<ChatMessage> messages) {
        return new ChatRequest("local", "model", "", "", List.of(), null, messages,
                List.of(new ToolSpec("talos.read_file", "Read", "{}")),
                new ChatRequestControls(ToolChoiceMode.AUTO, "", ResponseFormatMode.TEXT, "",
                        List.of("bounded-read-evidence-delivery")));
    }

    @Test void deliveryBoundsAreSeparateFromSynthesisBounds() throws Exception {
        var messages = new java.util.ArrayList<ChatMessage>();
        for (int index = 0; index < ProviderReadInputBinding.MAX_DELIVERY_TOOL_RESULTS; index++)
            messages.add(ChatMessage.toolResult("read-" + index, "current source"));
        assertTrue(selected(deliveryRequest(messages), body(messages)).readInputBinding().bound());
        assertEquals("PREPARED_INPUT_LIMIT", selected(request(messages), body(messages)).readInputBinding().reasonCode());
        messages.add(ChatMessage.toolResult("overflow", "source"));
        assertEquals("PREPARED_INPUT_LIMIT", selected(deliveryRequest(messages), body(messages)).readInputBinding().reasonCode());
        var atLimit = List.of(ChatMessage.toolResult("read", "x".repeat(ProviderReadInputBinding.MAX_DELIVERY_TOOL_RESULT_CHARS)));
        assertTrue(selected(deliveryRequest(atLimit), body(atLimit)).readInputBinding().bound());
        var above = List.of(ChatMessage.toolResult("read", atLimit.getFirst().content() + "x"));
        assertEquals("PREPARED_INPUT_LIMIT", selected(deliveryRequest(above), body(above)).readInputBinding().reasonCode());
    }

    @Test void anUnknownSanitizerAndAReplacedBodyHashCannotBind() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "source")));
        var generation = generation();
        var attempt = attempt(generation);
        attempt.bindPreparedReadInputs(request, "unknown-v2");
        var serialized = ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body(request.messages), List.of(), request);
        completeTransport(attempt, serialized);
        var receipt = attempt.respond(response("answer"));
        generation.complete(receipt);
        assertEquals("UNKNOWN_SANITIZER", receipt.readInputBinding().reasonCode());

        generation = generation();
        attempt = attempt(generation);
        attempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        completeTransport(attempt, new ProviderTransportHandoff.DispatchEvidence("sha256:" + "a".repeat(64),
                List.of(), java.util.Optional.empty(), serialized.readInputMembership()));
        receipt = attempt.respond(response("answer"));
        generation.complete(receipt);
        assertEquals("SERIALIZED_BODY_MISMATCH", receipt.readInputBinding().reasonCode());
    }

    @Test void retriesCannotInheritInputsAndPreparedInputsCannotBeRebound() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "source")));
        var generation = generation();
        var first = attempt(generation);
        first.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        assertThrows(IllegalStateException.class,
                () -> first.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION));
        var dispatch = ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body(request.messages), List.of(), request);
        completeTransport(first, dispatch);
        var stale = first.respond(response("first"));
        var retry = generation.startAttempt(ProviderResponseCapture.AttemptKind.TRANSIENT_RETRY,
                ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE);
        completeTransport(retry, dispatch);
        var chosen = retry.respond(response("second"));
        generation.complete(chosen);
        assertFalse(stale.readInputBinding().bound());
        assertEquals(ProviderReadInputBinding.Status.NOT_REQUESTED, chosen.readInputBinding().status());
    }

    @Test void absentScopeAndAbandonedReceiptsNeverBecomeBound() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "source")));
        var detached = ProviderResponseCapture.openGeneration(ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        var detachedAttempt = attempt(detached);
        detachedAttempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        var detachedReceipt = detachedAttempt.respond(response("answer"));
        assertFalse(detachedReceipt.readInputBinding().bound());

        var generation = generation();
        var attempt = attempt(generation);
        attempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        completeTransport(attempt, ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body(request.messages), List.of(), request));
        var receipt = attempt.respond(response("answer"));
        generation.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);
        generation.complete(receipt);
        assertFalse(receipt.readInputBinding().bound());
    }

    @Test void scopeOwnershipCannotBeRecreatedByReusingTheSameScopeIdentity() throws Exception {
        var request = request(List.of(ChatMessage.toolResult("one", "source")));
        var scope = ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {}, "same-scope");
        var generation = scope.openGeneration(ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        var attempt = attempt(generation);
        attempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        completeTransport(attempt, ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                body(request.messages), List.of(), request));
        var receipt = attempt.respond(response("answer"));
        generation.complete(receipt);
        assertTrue(receipt.belongsToCurrentScope());
        assertFalse(ProviderResponseCapture.Receipt.none().belongsToCurrentScope());
        var copied = new ProviderResponseCapture.Receipt(receipt.generationId(), receipt.attemptId(),
                receipt.attemptOrdinal(), receipt.source(), receipt.purpose(), receipt.completion(),
                true, false, receipt.assistantHash());
        assertFalse(copied.belongsToCurrentScope());
        scope.close();
        assertSame(scope, ProviderResponseCapture.currentScope());
        assertFalse(receipt.belongsToCurrentScope());
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {}, "same-scope");
        assertTrue(receipt.providerTerminalEligible());
        assertFalse(receipt.belongsToCurrentScope());
        ProviderResponseCapture.clear();
        assertFalse(receipt.belongsToCurrentScope());
    }

    private static ChatRequest request(List<ChatMessage> messages) {
        return new ChatRequest("local", "model", "", "", List.of(), null, messages, List.of(),
                new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "",
                        List.of(ProviderReadInputBinding.REQUEST_TAG)).withSingleAttempt(true));
    }

    private static String body(List<ChatMessage> messages) throws Exception {
        return JSON.writeValueAsString(Map.of("messages", messages.stream()
                .map(message -> Map.of("role", message.role(), "tool_call_id", message.toolCallId(),
                        "content", message.content())).toList()));
    }

    private static String bodyWithSchema(List<ChatMessage> messages, int schemaDepth) throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body(messages));
        String schema = "{\"additionalProperties\":".repeat(schemaDepth - 1)
                + "{\"type\":\"object\"}" + "}".repeat(schemaDepth - 1);
        root.putObject("response_format").put("type", "json_schema").putObject("json_schema")
                .set("schema", JSON.readTree(schema));
        return root.toString();
    }

    private static int depth(com.fasterxml.jackson.databind.JsonNode value) {
        if (!value.isContainerNode()) return 0;
        int maximum = 0;
        for (var child : value) maximum = Math.max(maximum, depth(child));
        return maximum + 1;
    }

    private static ProviderResponseCapture.Generation generation() {
        return ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {})
                .openGeneration(ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE);
    }

    private static ProviderResponseCapture.Attempt attempt(ProviderResponseCapture.Generation generation) {
        return generation.startAttempt(ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
    }

    private static ProviderResponseCapture.Response response(String answer) {
        return new ProviderResponseCapture.Response(answer, 0, ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS, false, false);
    }

    private static ProviderResponseCapture.Receipt selected(ChatRequest request, String body) {
        var generation = generation();
        var attempt = attempt(generation);
        attempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        completeTransport(attempt, ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body, List.of(), request));
        var receipt = attempt.respond(response("answer"));
        generation.complete(receipt);
        return receipt;
    }

    private static void completeTransport(ProviderResponseCapture.Attempt attempt,
                                          ProviderTransportHandoff.DispatchEvidence dispatch) {
        var transport = attempt.admitTransport(dispatch);
        try { transport.invokeSend(() -> null); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
        transport.responseHeadersObserved();
        transport.responseBodyOpened(() -> {});
        transport.providerTerminalObserved();
        transport.responseBodyCompleted();
    }
}
