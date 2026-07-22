package dev.talos.runtime.trace;

import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnSettlement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed writer/decoder for the abnormal turn-settlement trace event. */
public final class TurnSettlementTraceEvent {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_EXCEPTION_CLASS_CHARS = 256;
    private static final Set<String> KEYS = Set.of(
            "schemaVersion", "settlementKind", "stage", "errorKind",
            "errorCode", "exceptionClass", "causalOwner");

    private TurnSettlementTraceEvent() {}

    public enum DecodeStatus { ABSENT, VALID, INVALID }

    public record Decoded(
            DecodeStatus status,
            TurnSettlement.Kind kind,
            TurnExecutionStage stage,
            TurnFailureClassifier.ErrorKind errorKind,
            int errorCode,
            String exceptionClass
    ) {
        public boolean valid() {
            return status == DecodeStatus.VALID;
        }

        public boolean present() {
            return status != DecodeStatus.ABSENT;
        }

        static Decoded absent() {
            return new Decoded(DecodeStatus.ABSENT, null, null, null, 0, "");
        }

        static Decoded invalid() {
            return new Decoded(DecodeStatus.INVALID, null, null, null, 0, "");
        }
    }

    public static TurnTraceEvent create(
            String timestamp,
            TurnSettlement.Kind kind,
            TurnExecutionStage stage,
            TurnFailureClassifier.Classification failure
    ) {
        TurnFailureClassifier.Classification safeFailure = failure == null
                ? TurnFailureClassifier.Classification.none()
                : failure;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", SCHEMA_VERSION);
        data.put("settlementKind", kind.name());
        data.put("stage", (stage == null ? TurnExecutionStage.UNKNOWN : stage).name());
        data.put("errorKind", safeFailure.kind().name());
        data.put("errorCode", safeFailure.code());
        data.put("exceptionClass", safeFailure.exceptionClass());
        data.put("causalOwner", "UNKNOWN");
        return TurnTraceEvent.simple("TURN_SETTLED", timestamp, data);
    }

    public static Decoded decode(LocalTurnTrace trace) {
        if (trace == null || trace.events() == null || trace.events().isEmpty()) {
            return Decoded.absent();
        }
        List<TurnTraceEvent> settlements = trace.events().stream()
                .filter(event -> event != null && "TURN_SETTLED".equals(event.type()))
                .toList();
        if (settlements.isEmpty()) return Decoded.absent();
        long completions = trace.events().stream()
                .filter(event -> event != null
                        && "TRACE_COMPLETED".equals(event.type()))
                .count();
        if (settlements.size() != 1 || completions != 1 || trace.events().size() < 2) {
            return Decoded.invalid();
        }
        TurnTraceEvent event = settlements.getFirst();
        TurnTraceEvent completion = trace.events().getLast();
        if (trace.events().get(trace.events().size() - 2) != event
                || !"TRACE_COMPLETED".equals(completion.type())
                || !event.phase().isBlank() || !event.toolName().isBlank()
                || !completion.phase().isBlank() || !completion.toolName().isBlank()
                || !completion.data().isEmpty()) {
            return Decoded.invalid();
        }
        Map<String, Object> data = event.data();
        if (!KEYS.equals(data.keySet())
                || intValue(data.get("schemaVersion")) != SCHEMA_VERSION
                || !"UNKNOWN".equals(stringValue(data.get("causalOwner")))) {
            return Decoded.invalid();
        }
        TurnSettlement.Kind kind = enumValue(
                TurnSettlement.Kind.class, data.get("settlementKind"));
        TurnExecutionStage stage = enumValue(
                TurnExecutionStage.class, data.get("stage"));
        TurnFailureClassifier.ErrorKind errorKind = enumValue(
                TurnFailureClassifier.ErrorKind.class, data.get("errorKind"));
        int errorCode = intValue(data.get("errorCode"));
        String exceptionClass = stringValue(data.get("exceptionClass"));
        if (kind == null || stage == null || errorKind == null) return Decoded.invalid();

        boolean noResult = kind == TurnSettlement.Kind.NO_RESULT;
        boolean failed = kind == TurnSettlement.Kind.FAILED
                || kind == TurnSettlement.Kind.CANCELLED;
        if (!noResult && !failed) return Decoded.invalid();
        if (noResult) {
            if (errorKind != TurnFailureClassifier.ErrorKind.NONE
                    || errorCode != 0 || !exceptionClass.isEmpty()) {
                return Decoded.invalid();
            }
        } else if (errorKind == TurnFailureClassifier.ErrorKind.NONE
                || !validErrorCode(errorKind, errorCode)
                || !safeExceptionClass(exceptionClass)) {
            return Decoded.invalid();
        }
        if (kind == TurnSettlement.Kind.CANCELLED
                && errorKind != TurnFailureClassifier.ErrorKind.CANCELLATION) {
            return Decoded.invalid();
        }
        if (kind == TurnSettlement.Kind.FAILED
                && errorKind == TurnFailureClassifier.ErrorKind.CANCELLATION) {
            return Decoded.invalid();
        }
        return new Decoded(
                DecodeStatus.VALID, kind, stage, errorKind, errorCode, exceptionClass);
    }

    private static boolean safeExceptionClass(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_EXCEPTION_CLASS_CHARS) {
            return false;
        }
        return value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    }

    private static boolean validErrorCode(
            TurnFailureClassifier.ErrorKind kind,
            int code
    ) {
        return switch (kind) {
            case NONE -> code == 0;
            case ENGINE_MODEL_NOT_FOUND -> code == 404;
            case ENGINE_CONNECTION_FAILED, ENGINE_TRANSIENT -> code == 503;
            case ENGINE_CONTEXT_BUDGET -> code == 400;
            case ENGINE_MALFORMED_RESPONSE -> code == 502;
            case ENGINE_RESPONSE_ERROR -> code >= 100 && code <= 599;
            case TIMEOUT -> code == 408;
            case VALIDATION -> code == 400;
            case CANCELLATION, INTERRUPTION, UNEXPECTED -> code == 500;
        };
    }

    private static int intValue(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                    ? (int) number
                    : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value) {
        if (!(value instanceof String text)) return null;
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
