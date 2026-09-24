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
    public static final String SANITIZER_VERSION = "sanitize-message-content-v1";
    public static final int MAX_TOOL_RESULTS = 8;
    public static final int MAX_TOOL_RESULT_CHARS = 16_000;
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
        private final String reasonCode;
        private final String providerBodyHash;
        private final List<ToolResultMembership> toolResults;

        private SerializedMembership(boolean requested, String reasonCode, String providerBodyHash,
                                     List<ToolResultMembership> toolResults) {
            this.requested = requested;
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
                    && reasonCode.equals(value.reasonCode) && providerBodyHash.equals(value.providerBodyHash)
                    && toolResults.equals(value.toolResults);
        }

        @Override public int hashCode() {
            return Objects.hash(requested, reasonCode, providerBodyHash, toolResults);
        }
    }

    static final class PreparedInputs {
        private final boolean requested;
        private final String reasonCode;
        private final String sanitizerVersion;
        private final List<ToolResultInput> toolResults;

        private PreparedInputs(boolean requested, String reasonCode, String sanitizerVersion,
                               List<ToolResultInput> toolResults) {
            this.requested = requested;
            this.reasonCode = reasonCode;
            this.sanitizerVersion = sanitizerVersion;
            this.toolResults = List.copyOf(toolResults);
        }
    }

    private static final ProviderReadInputBinding NOT_REQUESTED = new ProviderReadInputBinding(
            Status.NOT_REQUESTED, "NOT_REQUESTED", "", "", List.of());
    private final Status status;
    private final String reasonCode;
    private final String sanitizerVersion;
    private final String providerBodyHash;
    private final List<ToolResultInput> preparedToolResults;

    private ProviderReadInputBinding(Status status, String reasonCode, String sanitizerVersion,
                                     String providerBodyHash, List<ToolResultInput> preparedToolResults) {
        this.status = status;
        this.reasonCode = reasonCode;
        this.sanitizerVersion = sanitizerVersion;
        this.providerBodyHash = providerBodyHash;
        this.preparedToolResults = List.copyOf(preparedToolResults);
    }

    public Status status() { return status; }
    public String reasonCode() { return reasonCode; }
    public String sanitizerVersion() { return sanitizerVersion; }
    public String providerBodyHash() { return providerBodyHash; }
    public List<ToolResultInput> preparedToolResults() { return preparedToolResults; }
    public boolean bound() { return status == Status.BOUND; }

    static ProviderReadInputBinding notRequested() { return NOT_REQUESTED; }

    static PreparedInputs noPreparedInputs() {
        return new PreparedInputs(false, "NOT_REQUESTED", "", List.of());
    }

    static SerializedMembership noSerializedMembership() {
        return new SerializedMembership(false, "NOT_REQUESTED", "", List.of());
    }

    private static ProviderReadInputBinding unavailable(String reason) {
        return new ProviderReadInputBinding(Status.UNAVAILABLE, reason, "", "", List.of());
    }

    ProviderReadInputBinding forSelectedResponse(boolean selected, boolean terminalEligible) {
        if (status == Status.NOT_REQUESTED) return this;
        if (!selected) return unavailable("RESPONSE_NOT_SELECTED");
        return terminalEligible ? this : unavailable("RESPONSE_NOT_TERMINAL");
    }

    private static boolean requested(ChatRequest request) {
        return request != null && request.controls.debugTags().contains(REQUEST_TAG);
    }

    private static boolean responseOnly(ChatRequest request) {
        return request.tools.isEmpty() && request.controls.toolChoice() == ToolChoiceMode.NONE
                && request.controls.toolAuthorityMode() == ProviderResponseCapture.ToolAuthorityMode.NONE;
    }

    static PreparedInputs prepare(ChatRequest request, String sanitizerVersion) {
        if (!requested(request)) return noPreparedInputs();
        if (!responseOnly(request)) return unavailablePrepared("REQUEST_NOT_RESPONSE_ONLY");
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
            if (inputs.size() >= MAX_TOOL_RESULTS
                    || message.content().length() > MAX_TOOL_RESULT_CHARS - characters)
                return unavailablePrepared("PREPARED_INPUT_LIMIT");
            characters += message.content().length();
            inputs.add(new ToolResultInput(id, message.content()));
        }
        if (inputs.isEmpty()) return unavailablePrepared("NO_PREPARED_TOOL_RESULTS");
        return new PreparedInputs(true, "", sanitizerVersion, inputs);
    }

    private static PreparedInputs unavailablePrepared(String reason) {
        return new PreparedInputs(true, reason, "", List.of());
    }

    static SerializedMembership decode(String body, ChatRequest request, String bodyHash) {
        if (!requested(request)) return noSerializedMembership();
        if (!responseOnly(request)) return unavailableSerialized("REQUEST_NOT_RESPONSE_ONLY");
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
                if (results.size() >= MAX_TOOL_RESULTS
                        || content.textValue().length() > MAX_TOOL_RESULT_CHARS - characters)
                    return unavailableSerialized("SERIALIZED_INPUT_LIMIT");
                characters += content.textValue().length();
                results.add(new ToolResultMembership(id, hash(content.textValue())));
            }
            if (results.isEmpty()) return unavailableSerialized("NO_SERIALIZED_TOOL_RESULTS");
            return new SerializedMembership(true, "", bodyHash, results);
        } catch (Exception invalid) {
            return unavailableSerialized("INVALID_SERIALIZED_JSON");
        }
    }

    private static SerializedMembership unavailableSerialized(String reason) {
        return new SerializedMembership(true, reason, "", List.of());
    }

    static ProviderReadInputBinding bind(PreparedInputs prepared,
                                         ProviderTransportHandoff.DispatchEvidence dispatch) {
        if (!prepared.requested) return notRequested();
        if (!prepared.reasonCode.isEmpty()) return unavailable(prepared.reasonCode);
        SerializedMembership serialized = dispatch.readInputMembership();
        if (!serialized.requested) return unavailable("SERIALIZED_INPUT_NOT_CAPTURED");
        if (!serialized.reasonCode.isEmpty()) return unavailable(serialized.reasonCode);
        if (dispatch.providerBodyHash().isBlank()
                || !dispatch.providerBodyHash().equals(serialized.providerBodyHash))
            return unavailable("SERIALIZED_BODY_MISMATCH");
        List<ToolResultMembership> expected = prepared.toolResults.stream()
                .map(input -> new ToolResultMembership(input.toolCallId(), hash(input.content())))
                .toList();
        if (!expected.equals(serialized.toolResults)) return unavailable("TOOL_RESULT_MEMBERSHIP_MISMATCH");
        return new ProviderReadInputBinding(Status.BOUND, "", prepared.sanitizerVersion,
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
