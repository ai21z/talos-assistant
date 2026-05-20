package dev.talos.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

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
            this(estimatedTokens, inputBudgetTokens, contextWindowTokens, removedMessages, 0);
        }

        public ContextBudgetExceeded(int estimatedTokens,
                                     int inputBudgetTokens,
                                     int contextWindowTokens,
                                     int removedMessages,
                                     int httpStatus) {
            super(contextBudgetMessage(estimatedTokens, inputBudgetTokens, contextWindowTokens),
                    null,
                    Math.max(0, httpStatus),
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

        private static String contextBudgetMessage(int estimatedTokens, int inputBudgetTokens, int contextWindowTokens) {
            return "Request exceeds context budget: estimated " + Math.max(0, estimatedTokens)
                    + " input tokens, budget " + Math.max(0, inputBudgetTokens)
                    + " input tokens, context window " + Math.max(0, contextWindowTokens)
                    + " tokens.";
        }
    }

    /** Catch-all for non-2xx responses that don't fit the above categories. */
    public static final class ResponseError extends EngineException {
        private final String bodyHash;
        private final int bodyChars;
        private final boolean bodyLooksContextBudgetExceeded;

        public ResponseError(int httpStatus, String body) {
            super(responseErrorMessage(httpStatus, body),
                    null, httpStatus, "");
            this.bodyHash = diagnosticHash(body);
            this.bodyChars = body == null ? 0 : body.length();
            this.bodyLooksContextBudgetExceeded = looksContextBudgetExceeded(body);
        }

        public ResponseError(int httpStatus, String body, Throwable cause) {
            super(responseErrorMessage(httpStatus, body),
                    cause, httpStatus, "");
            this.bodyHash = diagnosticHash(body);
            this.bodyChars = body == null ? 0 : body.length();
            this.bodyLooksContextBudgetExceeded = looksContextBudgetExceeded(body);
        }

        public String bodyHash() { return bodyHash; }

        public int bodyChars() { return bodyChars; }

        public boolean bodyLooksContextBudgetExceeded() { return bodyLooksContextBudgetExceeded; }
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
                    + diagnosticSuffix(body),
                    null,
                    0,
                    "The local model server returned an unsupported response shape.");
            this.context = safe(context);
            this.bodyPreview = "";
            this.bodyHash = diagnosticHash(body);
            this.bodyChars = body == null ? 0 : body.length();
        }

        public MalformedResponse(String context, String body, Throwable cause) {
            super("Malformed engine response"
                    + (context == null || context.isBlank() ? "" : " for " + context)
                    + diagnosticSuffix(body),
                    cause,
                    0,
                    "The local model server returned an unsupported response shape.");
            this.context = safe(context);
            this.bodyPreview = "";
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

    private static String responseErrorMessage(int httpStatus, String body) {
        return "Engine error (HTTP " + httpStatus + ")" + diagnosticSuffix(body);
    }

    private static String diagnosticSuffix(String body) {
        if (body == null) return "";
        return ": bodyHash=" + diagnosticHash(body) + " bodyChars=" + body.length();
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

    private static boolean looksContextBudgetExceeded(String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("exceeds")
                && (lower.contains("available context size")
                || lower.contains("context size")
                || lower.contains("context window")
                || lower.contains("context budget"));
    }

}

