package dev.talos.spi.types;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Prepared read inputs are associated with one locally admitted serialized request. */
public final class ProviderReadInputBinding {
    public static final String REQUEST_TAG = "bounded-read-only-synthesis";
    public static final String DELIVERY_TAG = "bounded-read-evidence-delivery";
    public static final String SANITIZER_VERSION = "sanitize-message-content-v1";
    public static final int MAX_TOOL_RESULTS = 8;
    public static final int MAX_TOOL_RESULT_CHARS = 16_000;
    public static final int MAX_DELIVERY_TOOL_RESULTS = 32;
    public static final int MAX_DELIVERY_TOOL_RESULT_CHARS = 32_000;
    public static final int MAX_SERIALIZED_BYTES = 256_000;
    // Schema depth 32 is enclosed by the body, response_format and json_schema objects.
    static final int MAX_SERIALIZED_NESTING_DEPTH = 35;
    private static final int MAX_MESSAGES = 64;
    private static final int MAX_CALL_ID_CHARS = 256;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_SERIALIZED_NESTING_DEPTH)
                    .maxStringLength(MAX_SERIALIZED_BYTES)
                    .maxNumberLength(128)
                    .build())
            .build());

    public enum Status { NOT_REQUESTED, UNAVAILABLE, BOUND }
    public enum Purpose { NONE, RESPONSE_ONLY, DELIVERY }

    public record ToolResultInput(String toolCallId, String content) {
        public ToolResultInput {
            toolCallId = Objects.requireNonNull(toolCallId, "tool result ID");
            content = Objects.requireNonNull(content, "tool result content");
        }

        @Override public String toString() {
            return "ToolResultInput[contentChars=" + content.length() + "]";
        }
    }

    public record ToolResultMembership(String toolCallId, String contentHash) {}

    /** Only decoded membership is retained. Provider JSON is not retained here. */
    public static final class SerializedMembership {
        private final boolean requested;
        private final Purpose purpose;
        private final String reasonCode;
        private final String providerBodyHash;
        private final List<ToolResultMembership> toolResults;

        private SerializedMembership(Purpose purpose, String reasonCode, String providerBodyHash,
                                     List<ToolResultMembership> toolResults) {
            this.requested = purpose != Purpose.NONE || !"NOT_REQUESTED".equals(reasonCode);
            this.purpose = purpose;
            this.reasonCode = reasonCode;
            this.providerBodyHash = providerBodyHash;
            this.toolResults = List.copyOf(toolResults);
        }

        public boolean available() { return requested && reasonCode.isEmpty(); }
        public String reasonCode() { return reasonCode; }
        public String providerBodyHash() { return providerBodyHash; }
        public List<ToolResultMembership> toolResults() { return toolResults; }

        @Override public boolean equals(Object other) {
            return other instanceof SerializedMembership value && requested == value.requested
                    && purpose == value.purpose
                    && reasonCode.equals(value.reasonCode) && providerBodyHash.equals(value.providerBodyHash)
                    && toolResults.equals(value.toolResults);
        }

        @Override public int hashCode() {
            return Objects.hash(purpose, reasonCode, providerBodyHash, toolResults);
        }
    }

    static final class PreparedInputs {
        private final boolean requested;
        private final Purpose purpose;
        private final String reasonCode;
        private final String sanitizerVersion;
        private final List<ToolResultInput> toolResults;

        private PreparedInputs(Purpose purpose, String reasonCode, String sanitizerVersion,
                               List<ToolResultInput> toolResults) {
            this.requested = purpose != Purpose.NONE || !"NOT_REQUESTED".equals(reasonCode);
            this.purpose = purpose;
            this.reasonCode = reasonCode;
            this.sanitizerVersion = sanitizerVersion;
            this.toolResults = List.copyOf(toolResults);
        }
    }

    private static final ProviderReadInputBinding NOT_REQUESTED = new ProviderReadInputBinding(
            Status.NOT_REQUESTED, Purpose.NONE, "NOT_REQUESTED", "", "", List.of());
    private final Status status;
    private final Purpose purpose;
    private final String reasonCode;
    private final String sanitizerVersion;
    private final String providerBodyHash;
    private final List<ToolResultInput> preparedToolResults;

    private ProviderReadInputBinding(Status status, Purpose purpose, String reasonCode, String sanitizerVersion,
                                     String providerBodyHash, List<ToolResultInput> preparedToolResults) {
        this.status = status;
        this.purpose = purpose;
        this.reasonCode = reasonCode;
        this.sanitizerVersion = sanitizerVersion;
        this.providerBodyHash = providerBodyHash;
        this.preparedToolResults = List.copyOf(preparedToolResults);
    }

    public Status status() { return status; }
    public Purpose purpose() { return purpose; }
    public String reasonCode() { return reasonCode; }
    public String sanitizerVersion() { return sanitizerVersion; }
    public String providerBodyHash() { return providerBodyHash; }
    public List<ToolResultInput> preparedToolResults() { return preparedToolResults; }
    public boolean bound() { return status == Status.BOUND; }

    static ProviderReadInputBinding notRequested() { return NOT_REQUESTED; }

    static PreparedInputs noPreparedInputs() {
        return new PreparedInputs(Purpose.NONE, "NOT_REQUESTED", "", List.of());
    }

    static SerializedMembership noSerializedMembership() {
        return new SerializedMembership(Purpose.NONE, "NOT_REQUESTED", "", List.of());
    }

    private static ProviderReadInputBinding unavailable(String reason) {
        return new ProviderReadInputBinding(Status.UNAVAILABLE, Purpose.NONE, reason, "", "", List.of());
    }

    ProviderReadInputBinding forSelectedResponse(boolean selected, boolean terminalEligible, boolean deliveryObserved) {
        if (status == Status.NOT_REQUESTED) return this;
        if (!selected) return unavailable("RESPONSE_NOT_SELECTED");
        // A selected truncated response can prove input delivery, never answer completion.
        if (purpose == Purpose.DELIVERY)
            return deliveryObserved ? this : unavailable("DELIVERY_TRANSPORT_NOT_OBSERVED");
        return terminalEligible ? this : unavailable("RESPONSE_NOT_TERMINAL");
    }

    private static boolean requested(ChatRequest request) {
        return purpose(request) != Purpose.NONE;
    }

    private static Purpose purpose(ChatRequest request) {
        if (request == null) return Purpose.NONE;
        if (request.controls.debugTags().contains(REQUEST_TAG)) return Purpose.RESPONSE_ONLY;
        return request.controls.debugTags().contains(DELIVERY_TAG) ? Purpose.DELIVERY : Purpose.NONE;
    }

    private static int resultLimit(ChatRequest request) {
        return purpose(request) == Purpose.DELIVERY ? MAX_DELIVERY_TOOL_RESULTS : MAX_TOOL_RESULTS;
    }

    private static int characterLimit(ChatRequest request) {
        return purpose(request) == Purpose.DELIVERY ? MAX_DELIVERY_TOOL_RESULT_CHARS : MAX_TOOL_RESULT_CHARS;
    }

    private static boolean responseOnly(ChatRequest request) {
        return request.tools.isEmpty() && request.controls.toolChoice() == ToolChoiceMode.NONE
                && request.controls.toolAuthorityMode() == ProviderResponseCapture.ToolAuthorityMode.NONE;
    }

    static PreparedInputs prepare(ChatRequest request, String sanitizerVersion) {
        if (!requested(request)) return noPreparedInputs();
        if (purpose(request) == Purpose.RESPONSE_ONLY && !responseOnly(request))
            return unavailablePrepared("REQUEST_NOT_RESPONSE_ONLY");
        if (!SANITIZER_VERSION.equals(sanitizerVersion))
            return unavailablePrepared("UNKNOWN_SANITIZER");
        if (request.messages.size() > MAX_MESSAGES) return unavailablePrepared("PREPARED_INPUT_LIMIT");
        List<ToolResultInput> inputs = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        int characters = 0;
        for (ChatMessage message : request.messages) {
            if (message == null) return unavailablePrepared("INVALID_PREPARED_MESSAGE");
            if (!"tool".equals(message.role())) continue;
            String id = message.toolCallId();
            if (!validId(id) || message.content() == null || !validUnicode(message.content())
                    || message.hasNativeToolCalls())
                return unavailablePrepared("INVALID_PREPARED_TOOL_RESULT");
            if (!ids.add(id)) return unavailablePrepared("DUPLICATE_PREPARED_TOOL_ID");
            if (inputs.size() >= resultLimit(request)
                    || message.content().length() > characterLimit(request) - characters)
                return unavailablePrepared("PREPARED_INPUT_LIMIT");
            characters += message.content().length();
            inputs.add(new ToolResultInput(id, message.content()));
        }
        if (inputs.isEmpty()) return unavailablePrepared("NO_PREPARED_TOOL_RESULTS");
        return new PreparedInputs(purpose(request), "", sanitizerVersion, inputs);
    }

    private static PreparedInputs unavailablePrepared(String reason) {
        return new PreparedInputs(Purpose.NONE, reason, "", List.of());
    }

    static SerializedMembership decode(String body, ChatRequest request, String bodyHash) {
        if (!requested(request)) return noSerializedMembership();
        if (purpose(request) == Purpose.RESPONSE_ONLY && !responseOnly(request))
            return unavailableSerialized("REQUEST_NOT_RESPONSE_ONLY");
        if (body == null || body.length() > MAX_SERIALIZED_BYTES
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_SERIALIZED_BYTES)
            return unavailableSerialized("SERIALIZED_INPUT_LIMIT");
        try (JsonParser parser = JSON.createParser(body)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null)
                return unavailableSerialized("INVALID_SERIALIZED_JSON");
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray())
                return unavailableSerialized("UNSUPPORTED_SERIALIZED_MESSAGES");
            if (messages.size() > MAX_MESSAGES) return unavailableSerialized("SERIALIZED_INPUT_LIMIT");
            List<ToolResultMembership> results = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            int characters = 0;
            for (JsonNode message : messages) {
                if (!message.isObject() || !message.path("role").isTextual())
                    return unavailableSerialized("INVALID_SERIALIZED_MESSAGE");
                if (!"tool".equals(message.path("role").textValue())) continue;
                JsonNode idNode = message.get("tool_call_id");
                JsonNode content = message.get("content");
                if (idNode == null || !idNode.isTextual() || !validId(idNode.textValue())
                        || content == null || !content.isTextual() || !validUnicode(content.textValue())
                        || message.has("tool_calls"))
                    return unavailableSerialized("INVALID_SERIALIZED_TOOL_RESULT");
                String id = idNode.textValue();
                if (!ids.add(id)) return unavailableSerialized("DUPLICATE_SERIALIZED_TOOL_ID");
                if (results.size() >= resultLimit(request)
                        || content.textValue().length() > characterLimit(request) - characters)
                    return unavailableSerialized("SERIALIZED_INPUT_LIMIT");
                characters += content.textValue().length();
                results.add(new ToolResultMembership(id, hash(content.textValue())));
            }
            if (results.isEmpty()) return unavailableSerialized("NO_SERIALIZED_TOOL_RESULTS");
            return new SerializedMembership(purpose(request), "", bodyHash, results);
        } catch (Exception invalid) {
            return unavailableSerialized("INVALID_SERIALIZED_JSON");
        }
    }

    private static SerializedMembership unavailableSerialized(String reason) {
        return new SerializedMembership(Purpose.NONE, reason, "", List.of());
    }

    static ProviderReadInputBinding bind(PreparedInputs prepared,
                                         ProviderTransportHandoff.DispatchEvidence dispatch) {
        if (!prepared.requested) return notRequested();
        if (!prepared.reasonCode.isEmpty()) return unavailable(prepared.reasonCode);
        SerializedMembership serialized = dispatch.readInputMembership();
        if (!serialized.requested) return unavailable("SERIALIZED_INPUT_NOT_CAPTURED");
        if (!serialized.reasonCode.isEmpty()) return unavailable(serialized.reasonCode);
        if (prepared.purpose != serialized.purpose) return unavailable("INPUT_PURPOSE_MISMATCH");
        if (dispatch.providerBodyHash().isBlank()
                || !dispatch.providerBodyHash().equals(serialized.providerBodyHash))
            return unavailable("SERIALIZED_BODY_MISMATCH");
        List<ToolResultMembership> expected = prepared.toolResults.stream()
                .map(input -> new ToolResultMembership(input.toolCallId(), hash(input.content())))
                .toList();
        if (!expected.equals(serialized.toolResults)) return unavailable("TOOL_RESULT_MEMBERSHIP_MISMATCH");
        return new ProviderReadInputBinding(Status.BOUND, prepared.purpose, "", prepared.sanitizerVersion,
                serialized.providerBodyHash, prepared.toolResults);
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.equals(id.strip()) && id.length() <= MAX_CALL_ID_CHARS
                && validUnicode(id);
    }

    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
