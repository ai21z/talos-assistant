package dev.talos.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sealed exception hierarchy for model-engine errors.
 *
 * <p>Subtypes carry structured metadata (HTTP status, user-facing guidance)
 * so callers can classify errors without string-matching on messages.
 *
 * <p>Unchecked so that existing {@code throws Exception} SPI signatures
 * remain source-compatible while callers can pattern-match in catch blocks.
 */
public sealed class EngineException extends RuntimeException
        permits EngineException.ModelNotFound,
                EngineException.ConnectionFailed,
                EngineException.Transient,
                EngineException.ContextBudgetExceeded,
                EngineException.ResponseError,
                EngineException.MalformedResponse {

    private final int httpStatus;
    private final String guidance;
    private static final Pattern SECRET_LIKE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(secret|token|api[_-]?key|password|credential|credentials)\\b\\s*=\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\s,;]+)");

    protected EngineException(String message, Throwable cause, int httpStatus, String guidance) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.guidance = guidance;
    }

    /** The HTTP status code that triggered this error, or 0 if not HTTP-related. */
    public int httpStatus() { return httpStatus; }

    /** User-facing guidance on how to resolve the error (never null, may be empty). */
    public String guidance() { return guidance == null ? "" : guidance; }

    // ── Subtypes ──────────────────────────────────────────────────────────

    /** Model was not found on the backend (HTTP 404). */
    public static final class ModelNotFound extends EngineException {
        private final String model;

        public ModelNotFound(String model) {
            this(model, null);
        }

        public ModelNotFound(String model, Throwable cause) {
            super("Model not found: " + model, cause, 404,
                    "Configure or download the model for the selected backend, then run talos status --verbose.");
            this.model = model == null ? "" : model;
        }

        public String model() { return model; }
    }

    /** Backend is unreachable (connection refused, DNS failure, etc.). */
    public static final class ConnectionFailed extends EngineException {
        public ConnectionFailed(String host, Throwable cause) {
            super("Cannot connect to backend at " + host, cause, 0,
                    "Check the selected model engine with talos status --verbose.");
        }
    }

    /** Transient / retryable error (HTTP 503, 429, timeout during generation). */
    public static final class Transient extends EngineException {
        public Transient(String message, Throwable cause, int httpStatus) {
            super(message, cause, httpStatus,
                    "Temporary error — please try again.");
        }

        public Transient(String message, int httpStatus) {
            this(message, null, httpStatus);
        }
    }

    /** Request cannot fit the selected model context after safe local trimming. */
    public static final class ContextBudgetExceeded extends EngineException {
        private final int estimatedTokens;
        private final int inputBudgetTokens;
        private final int contextWindowTokens;
        private final int removedMessages;

        public ContextBudgetExceeded(int estimatedTokens,
                                     int inputBudgetTokens,
                                     int contextWindowTokens,
                                     int removedMessages) {
            super("Request exceeds context budget: estimated " + Math.max(0, estimatedTokens)
                            + " input tokens, budget " + Math.max(0, inputBudgetTokens)
                            + " input tokens, context window " + Math.max(0, contextWindowTokens)
                            + " tokens.",
                    null,
                    0,
                    "Clear the session, shorten the request, or select a model/context window that can fit the current turn.");
            this.estimatedTokens = Math.max(0, estimatedTokens);
            this.inputBudgetTokens = Math.max(0, inputBudgetTokens);
            this.contextWindowTokens = Math.max(0, contextWindowTokens);
            this.removedMessages = Math.max(0, removedMessages);
        }

        public int estimatedTokens() { return estimatedTokens; }

        public int inputBudgetTokens() { return inputBudgetTokens; }

        public int contextWindowTokens() { return contextWindowTokens; }

        public int removedMessages() { return removedMessages; }
    }

    /** Catch-all for non-2xx responses that don't fit the above categories. */
    public static final class ResponseError extends EngineException {
        public ResponseError(int httpStatus, String body) {
            super("Engine error (HTTP " + httpStatus + ")" + (body != null ? ": " + truncate(body, 200) : ""),
                    null, httpStatus, "");
        }

        public ResponseError(int httpStatus, String body, Throwable cause) {
            super("Engine error (HTTP " + httpStatus + ")" + (body != null ? ": " + truncate(body, 200) : ""),
                    cause, httpStatus, "");
        }
    }

    /** Backend returned HTTP success with a response shape the engine cannot use. */
    public static final class MalformedResponse extends EngineException {
        private final String context;
        private final String bodyPreview;
        private final String bodyHash;
        private final int bodyChars;

        public MalformedResponse(String context, String body) {
            super("Malformed engine response"
                    + (context == null || context.isBlank() ? "" : " for " + context)
                    + (body != null ? ": " + truncate(body, 200) : ""),
                    null,
                    0,
                    "The local model server returned an unsupported response shape.");
            this.context = safe(context);
            this.bodyPreview = diagnosticPreview(body);
            this.bodyHash = diagnosticHash(body);
            this.bodyChars = body == null ? 0 : body.length();
        }

        public MalformedResponse(String context, String body, Throwable cause) {
            super("Malformed engine response"
                    + (context == null || context.isBlank() ? "" : " for " + context)
                    + (body != null ? ": " + truncate(body, 200) : ""),
                    cause,
                    0,
                    "The local model server returned an unsupported response shape.");
            this.context = safe(context);
            this.bodyPreview = diagnosticPreview(body);
            this.bodyHash = diagnosticHash(body);
            this.bodyChars = body == null ? 0 : body.length();
        }

        public String context() { return context; }

        public String bodyPreview() { return bodyPreview; }

        public String bodyHash() { return bodyHash; }

        public int bodyChars() { return bodyChars; }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static String safe(String s) {
        return s == null ? "" : s.strip();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String diagnosticPreview(String body) {
        if (body == null || body.isBlank()) return "";
        return truncate(redactSecretLikeAssignments(body.strip()), 500);
    }

    private static String diagnosticHash(String body) {
        String safeBody = body == null ? "" : body;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(safeBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }

    private static String redactSecretLikeAssignments(String text) {
        if (text == null || text.isBlank()) return text;
        Matcher matcher = SECRET_LIKE_ASSIGNMENT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(key + "=[redacted]"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

