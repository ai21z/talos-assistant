package dev.talos.spi.types;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Diagnostics-only membership of exact string roles and content in a serialized
 * provider body's top-level messages array. No request object or raw content is
 * retained. Membership proves presence, not ordering, multiplicity or execution.
 *
 * <p>Decoding is bounded to 4 MiB of UTF-8 input, nesting depth 64, decoded strings
 * of 1 MiB characters, 4,096 messages and roles of 256 characters. Unknown is
 * returned for missing messages, unsupported content shapes, malformed JSON,
 * duplicate fields or exceeded limits. A valid empty messages array is observed.
 * The exact body hash is retained even for unknown membership when input is
 * bounded. Over-limit and absent input have no body hash.
 */
public final class ProviderContextMembership {
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    public static final int MAX_STRING_CHARS = 1024 * 1024;
    public static final int MAX_MESSAGES = 4096;
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_ROLE_CHARS = 256;

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxStringLength(MAX_STRING_CHARS)
                    .maxNameLength(MAX_STRING_CHARS)
                    .maxNumberLength(128)
                    .build())
            .build();
    private static final ProviderContextMembership UNKNOWN =
            new ProviderContextMembership(false, "", Set.of());

    /** Content is represented only by its exact UTF-8 SHA-256 hash. */
    public record MessageKey(String role, String contentHash) {
        public MessageKey {
            Objects.requireNonNull(role, "message role");
            Objects.requireNonNull(contentHash, "message content hash");
            if (role.length() > MAX_ROLE_CHARS || !validUnicode(role)
                    || !contentHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid bounded message key");
            }
        }

        @Override public String toString() {
            return "MessageKey[roleChars=" + role.length() + ", contentHash=" + contentHash + "]";
        }
    }

    private final boolean observed;
    private final String bodyHash;
    private final Set<MessageKey> messages;

    private ProviderContextMembership(boolean observed, String bodyHash, Set<MessageKey> messages) {
        this.observed = observed;
        this.bodyHash = bodyHash;
        this.messages = Set.copyOf(messages);
    }

    public boolean observed() { return observed; }
    public String bodyHash() { return bodyHash; }
    public boolean contains(MessageKey key) { return observed && key != null && messages.contains(key); }
    public static ProviderContextMembership unknown() { return UNKNOWN; }

    /** Role and content are used exactly, without trimming or role normalization. */
    public static MessageKey key(String role, String content) {
        Objects.requireNonNull(content, "message content");
        if (content.length() > MAX_STRING_CHARS || !validUnicode(content)) {
            throw new IllegalArgumentException("invalid bounded message content");
        }
        return new MessageKey(role, hash(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Invalid or unsupported bodies are represented by unknown membership. */
    public static ProviderContextMembership decode(String body) {
        if (body == null || body.length() > MAX_BODY_BYTES) return unknown();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) return unknown();
        String bodyHash = hash(bytes);
        ProviderContextMembership unavailable = new ProviderContextMembership(false, bodyHash, Set.of());
        try (JsonParser parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return unavailable;
            Set<MessageKey> messages = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return unavailable;
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("messages".equals(field)) {
                    if (value != JsonToken.START_ARRAY) return unavailable;
                    messages = readMessages(parser);
                    if (messages == null) return unavailable;
                } else {
                    if (!consumeValue(parser)) return unavailable;
                }
            }
            if (messages == null || parser.nextToken() != null) return unavailable;
            return new ProviderContextMembership(true, bodyHash, messages);
        } catch (IOException | RuntimeException invalid) {
            return unavailable;
        }
    }

    private static Set<MessageKey> readMessages(JsonParser parser) throws IOException {
        Set<MessageKey> messages = new HashSet<>();
        int count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++count > MAX_MESSAGES || parser.currentToken() != JsonToken.START_OBJECT) return null;
            String role = null;
            String content = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("role".equals(field) || "content".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return null;
                    String exact = parser.getText();
                    if (exact.length() > MAX_STRING_CHARS || !validUnicode(exact)) return null;
                    if ("role".equals(field)) {
                        if (exact.length() > MAX_ROLE_CHARS) return null;
                        role = exact;
                    } else {
                        content = exact;
                    }
                } else {
                    if (!consumeValue(parser)) return null;
                }
            }
            if (role == null || content == null) return null;
            messages.add(key(role, content));
        }
        return messages;
    }

    // Strings in unrelated body fields are also materialized briefly so parser
    // limits apply to lazily decoded strings, including nested metadata.
    private static boolean consumeValue(JsonParser parser) throws IOException {
        int depth = 0;
        do {
            JsonToken token = parser.currentToken();
            if (token == null) return false;
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++;
            if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
            if (token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME) {
                String value = parser.getText();
                if (value.length() > MAX_STRING_CHARS || !validUnicode(value)) return false;
            }
            if (depth == 0) return true;
        } while (parser.nextToken() != null);
        return false;
    }

    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (Character.isHighSurrogate(next)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(next)) {
                return false;
            }
        }
        return true;
    }

    private static String hash(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof ProviderContextMembership value && observed == value.observed
                && bodyHash.equals(value.bodyHash) && messages.equals(value.messages);
    }

    @Override public int hashCode() { return Objects.hash(observed, bodyHash, messages); }

    @Override public String toString() {
        return "ProviderContextMembership[observed=" + observed + ", bodyHash=" + bodyHash
                + ", messageCount=" + messages.size() + "]";
    }
}
