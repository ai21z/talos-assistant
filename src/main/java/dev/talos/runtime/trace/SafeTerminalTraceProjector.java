package dev.talos.runtime.trace;

import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnSettlement;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.tools.ToolAliasPolicy;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact-key, bounded-value projection for terminal lifecycle trace events. */
final class SafeTerminalTraceProjector {
    private static final Set<String> GENERATION = Set.of(
            "providerLedgerVersion", "ledgerSequence", "generationId",
            "source", "purpose", "toolAuthorityMode");
    private static final Set<String> ATTEMPT = union(GENERATION, Set.of(
            "attemptId", "attemptOrdinal", "attemptKind", "retryReason",
            "requestDispatched"));
    private static final Set<String> PROCESS_RECEIPT =
            ProviderProcessLaunchReceipt.TRACE_FIELDS;
    private static final Set<String> TRACE_STARTED = Set.of(
            "turnNumber", "redactionMode", "runtimeArtifactKind",
            "runtimeArtifactSha256", "runtimeArtifactSizeBytes",
            "runtimeClasspathSha256", "runtimeClasspathSizeBytes",
            "runtimeClasspathEntryCount", "runtimeVersion", "runtimeCommit",
            "initialization");
    private static final Set<String> SETTLEMENT = Set.of(
            "schemaVersion", "settlementKind", "stage", "errorKind",
            "errorCode", "exceptionClass", "causalOwner");
    private static final Set<String> RECOVERY = Set.of(
            "schemaVersion", "exceptionClass");
    private static final Set<String> RESPONSE = union(ATTEMPT, Set.of(
            "assistantHash", "assistantChars", "nativeToolCallCount",
            "capturedNativeToolProposalCount", "nativeToolProposalsTruncated",
            "textualToolCallPresent", "completion", "providerBacked",
            "foreground", "completedNormally", "normalized", "truncated"));
    private static final Set<String> PROPOSAL = union(ATTEMPT, Set.of(
            "proposalOrdinal", "providerCallIdHash", "toolNameClassification",
            "toolNameHash", "toolNameChars", "argumentNameHashes",
            "argumentCount", "argumentNameHashesTruncated", "argumentsHash"));
    private static final Set<String> AUTHORITY_SURFACE = Set.of(
            "schemaVersion", "bound", "toolChoiceMode", "namedToolNameHash",
            "selectedToolNameHashes", "authorizedToolNameHashes");
    private static final Set<String> TRANSPORT = Set.of(
            "transportLedgerVersion", "transportSequence", "generationId",
            "attemptId", "attemptOrdinal", "transportOperationId", "transition",
            "observationMode", "settlement", "transportFailure", "cancellationState",
            "cancellationReason", "wireDisposition", "quarantineRequired",
            "lateAfterGenerationTerminal", "processBinding");
    private static final Set<String> PROCESS_BOUND_TRANSPORT =
            union(TRANSPORT, Set.of("processReceiptId"));

    private SafeTerminalTraceProjector() {}

    record Projection(
            List<TurnTraceEvent> events,
            boolean providerLifecycleObserved,
            boolean providerLifecyclePreserved,
            boolean terminalizationRecovery
    ) {
        Projection {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    static Projection project(List<TurnTraceEvent> events, String traceId) {
        List<TurnTraceEvent> projected = new ArrayList<>();
        boolean providerObserved = false;
        boolean providerPreserved = true;
        boolean recovery = false;
        for (TurnTraceEvent event : events == null ? List.<TurnTraceEvent>of() : events) {
            if (event == null) continue;
            String type = event.type();
            boolean providerEvent = providerEvent(type);
            if (providerEvent) providerObserved = true;
            EventSpec spec = spec(type);
            if (spec == null) {
                if (providerEvent) providerPreserved = false;
                continue;
            }
            TurnTraceEvent safe = project(event, spec, traceId);
            if (safe == null) {
                if (providerEvent) providerPreserved = false;
                continue;
            }
            if ("TURN_TERMINALIZATION_RECOVERED".equals(type)) recovery = true;
            projected.add(safe);
        }
        return new Projection(
                projected, providerObserved, providerPreserved, recovery);
    }

    private static TurnTraceEvent project(
            TurnTraceEvent event,
            EventSpec spec,
            String traceId
    ) {
        if (!event.phase().isBlank() || !event.toolName().isBlank()) return null;
        Map<String, Object> data = event.data();
        if (!spec.allowed().containsAll(data.keySet())
                || !data.keySet().containsAll(spec.required())) {
            return null;
        }
        if (!validEventData(event.type(), data, traceId)) return null;
        if ("MODEL_IDENTITY_RESOLVED".equals(event.type())) {
            try {
                ModelIdentitySnapshot.fromTraceData(data);
            } catch (RuntimeException invalid) {
                return null;
            }
            return TurnTraceEvent.simple(
                    event.type(), safeTimestamp(event.timestamp()), data);
        }
        Map<String, Object> safeData = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object safe = safeValue(entry.getKey(), entry.getValue());
            if (safe == null) return null;
            safeData.put(entry.getKey(), safe);
        }
        return TurnTraceEvent.simple(event.type(), safeTimestamp(event.timestamp()), safeData);
    }

    private static Object safeValue(String key, Object value) {
        if (value instanceof Boolean) {
            return booleanKey(key) ? value : null;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            return validNumber(key, number) ? value : null;
        }
        if (value instanceof String text) {
            return validText(key, text) ? text : null;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 256 || !listKey(key)) return null;
            List<String> safe = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof String text) || !validListItem(key, text)) return null;
                safe.add(text);
            }
            return List.copyOf(safe);
        }
        if ("toolAuthoritySurface".equals(key) && value instanceof Map<?, ?> map) {
            if (!AUTHORITY_SURFACE.equals(map.keySet())) return null;
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String nestedKey)) return null;
                Object nestedValue = safeValue(nestedKey, entry.getValue());
                if (nestedValue == null) return null;
                safe.put(nestedKey, nestedValue);
            }
            return Map.copyOf(safe);
        }
        return null;
    }

    private static boolean validEventData(
            String type,
            Map<String, Object> data,
            String traceId
    ) {
        if (type == null) return false;
        if (type.startsWith("GENERATION_")
                || "PROVIDER_TOOL_PROPOSAL_CAPTURED".equals(type)) {
            String generationId = text(data, "generationId");
            if (!validGenerationId(generationId, traceId)
                    || !canonicalSourcePurpose(data)) {
                return false;
            }
            if (data.containsKey("attemptId")) {
                long ordinal = number(data, "attemptOrdinal");
                if (ordinal <= 0 || !text(data, "attemptId").equals(
                        generationId + "-attempt-" + ordinal)) {
                    return false;
                }
            }
            if (data.containsKey("selectedAttemptId")) {
                long ordinal = number(data, "selectedAttemptOrdinal");
                if (ordinal <= 0 || !text(data, "selectedAttemptId").equals(
                        generationId + "-attempt-" + ordinal)) {
                    return false;
                }
            }
            String operationId = text(data, "transportOperationId");
            String processReceiptId = text(data, "processReceiptId");
            if (!processReceiptId.isEmpty() && operationId.isEmpty()) {
                return false;
            }
            if (!operationId.isEmpty() && data.containsKey("attemptId")
                    && !operationId.equals(text(data, "attemptId") + "-transport-1")) {
                return false;
            }
            if (!"FOREGROUND_PROVIDER".equals(text(data, "source"))
                    && !"NONE".equals(text(data, "toolAuthorityMode"))) {
                return false;
            }
        }
        if ("GENERATION_REQUEST_DISPATCHED".equals(type)
                && !Boolean.TRUE.equals(data.get("requestDispatched"))) {
            return false;
        }
        if ("GENERATION_TRANSPORT_UNOBSERVED".equals(type)
                && Boolean.TRUE.equals(data.get("requestDispatched"))) {
            return false;
        }
        if ("GENERATION_RESPONSE_CAPTURED".equals(type)) {
            boolean dispatched = Boolean.TRUE.equals(data.get("requestDispatched"));
            boolean providerBacked = Boolean.TRUE.equals(data.get("providerBacked"));
            boolean completedNormally = Boolean.TRUE.equals(data.get("completedNormally"));
            if ((providerBacked || completedNormally) && !dispatched) return false;
        }
        if (("GENERATION_RESPONSE_CAPTURED".equals(type)
                || "GENERATION_COMPLETED".equals(type))
                && data.containsKey("transportOperationId")) {
            String attemptId = "GENERATION_COMPLETED".equals(type)
                    ? text(data, "selectedAttemptId")
                    : text(data, "attemptId");
            if (!text(data, "transportOperationId").equals(
                    attemptId + "-transport-1")) {
                return false;
            }
        }
        if ("PROVIDER_TRANSPORT_LIFECYCLE".equals(type)) {
            String generationId = text(data, "generationId");
            long ordinal = number(data, "attemptOrdinal");
            String attemptId = text(data, "attemptId");
            String operationId = text(data, "transportOperationId");
            ProviderTransportAttempt.Transition transition = enumValue(
                    ProviderTransportAttempt.Transition.class, text(data, "transition"));
            ProviderTransportAttempt.Settlement settlement = enumValue(
                    ProviderTransportAttempt.Settlement.class, text(data, "settlement"));
            if (!validGenerationId(generationId, traceId)
                    || ordinal <= 0
                    || !attemptId.equals(generationId + "-attempt-" + ordinal)
                    || !operationId.equals(attemptId + "-transport-1")
                    || transition == null || settlement == null) {
                return false;
            }
            if ((transition == ProviderTransportAttempt.Transition.SETTLED)
                    != (settlement != ProviderTransportAttempt.Settlement.NONE)) {
                return false;
            }
            long ledgerVersion = number(data, "transportLedgerVersion");
            String processReceiptId = text(data, "processReceiptId");
            ProviderTransportAttempt.ProcessBinding processBinding = enumValue(
                    ProviderTransportAttempt.ProcessBinding.class,
                    text(data, "processBinding"));
            if ((ledgerVersion == 1
                        && (processBinding != ProviderTransportAttempt.ProcessBinding.UNAVAILABLE
                            || !processReceiptId.isEmpty()))
                    || (ledgerVersion == 2
                        && ((processBinding
                                    == ProviderTransportAttempt.ProcessBinding.UNAVAILABLE)
                            != processReceiptId.isEmpty()))) {
                return false;
            }
        }
        if ("PROVIDER_PROCESS_LAUNCH_RECEIPT".equals(type)) {
            return validProcessReceipt(data);
        }
        if ("TURN_SETTLED".equals(type)) return validSettlement(data);
        if ("TRACE_STARTED".equals(type)) return validTraceStarted(data);
        return true;
    }

    private static boolean validProcessReceipt(Map<String, Object> data) {
        try {
            ProviderProcessLaunchReceipt receipt = new ProviderProcessLaunchReceipt(
                    number(data, "processId"),
                    number(data, "processStartEpochMilli"),
                    number(data, "launchObservedEpochMilli"),
                    number(data, "readinessObservedEpochMilli"),
                    text(data, "launchNonce"),
                    text(data, "candidateDeclarationSha256"),
                    text(data, "engineExecutableSha256"),
                    text(data, "modelArtifactSha256"),
                    text(data, "launchCommandSha256"),
                    enumValue(ProviderProcessLaunchReceipt.LaunchArgumentSet.class,
                            text(data, "launchArgumentSet")),
                    text(data, "effectiveEnvironmentSha256"),
                    enumValue(ProviderProcessLaunchReceipt.EnvironmentContract.class,
                            text(data, "environmentContract")),
                    text(data, "managedHost"),
                    Math.toIntExact(number(data, "managedPort")),
                    Boolean.TRUE.equals(data.get("prelaunchNoListener")),
                    text(data, "propsResponseSha256"),
                    text(data, "resolvedProfile"),
                    enumValue(ProviderProcessLaunchReceipt.ServedIdentity.class,
                            text(data, "servedIdentity")),
                    enumValue(ProviderProcessLaunchReceipt.EndpointObserverKind.class,
                            text(data, "endpointObserverKind")),
                    text(data, "endpointObserverIdentitySha256"),
                    enumValue(ProviderProcessLaunchReceipt.EndpointOwnershipBoundary.class,
                            text(data, "endpointOwnershipBoundary")),
                    enumValue(ProviderProcessLaunchReceipt.StableEndpointAssumption.class,
                            text(data, "stableEndpointAssumption")),
                    enumValue(ProviderProcessLaunchReceipt.ContentVerificationBoundary.class,
                            text(data, "contentVerificationBoundary")),
                    enumValue(ProviderProcessLaunchReceipt.StableFilesystemAssumption.class,
                            text(data, "stableFilesystemAssumption")));
            return PROCESS_RECEIPT.equals(data.keySet())
                    && receipt.receiptId().equals(text(data, "processReceiptId"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean validTraceStarted(Map<String, Object> data) {
        String kind = text(data, "runtimeArtifactKind");
        if (!kind.isEmpty() && !Set.of("JAR", "EXPLODED_CLASSES", "UNAVAILABLE").contains(kind)) {
            return false;
        }
        String initialization = text(data, "initialization");
        return initialization.isEmpty()
                || Set.of("DEGRADED", "TERMINALIZATION_RECOVERY").contains(initialization);
    }

    private static boolean validSettlement(Map<String, Object> data) {
        TurnSettlement.Kind kind = enumValue(
                TurnSettlement.Kind.class, text(data, "settlementKind"));
        TurnExecutionStage stage = enumValue(
                TurnExecutionStage.class, text(data, "stage"));
        TurnFailureClassifier.ErrorKind errorKind = enumValue(
                TurnFailureClassifier.ErrorKind.class, text(data, "errorKind"));
        long rawErrorCode = number(data, "errorCode");
        if (rawErrorCode < 0 || rawErrorCode > 599) return false;
        int errorCode = (int) rawErrorCode;
        String exceptionClass = text(data, "exceptionClass");
        if (kind == null || stage == null || errorKind == null
                || kind == TurnSettlement.Kind.COMPLETED
                || !"UNKNOWN".equals(text(data, "causalOwner"))) {
            return false;
        }
        if (kind == TurnSettlement.Kind.NO_RESULT) {
            return errorKind == TurnFailureClassifier.ErrorKind.NONE
                    && errorCode == 0 && exceptionClass.isEmpty();
        }
        if (kind == TurnSettlement.Kind.CANCELLED
                && errorKind != TurnFailureClassifier.ErrorKind.CANCELLATION) {
            return false;
        }
        if (kind == TurnSettlement.Kind.FAILED
                && (errorKind == TurnFailureClassifier.ErrorKind.NONE
                || errorKind == TurnFailureClassifier.ErrorKind.CANCELLATION)) {
            return false;
        }
        return validErrorCode(errorKind, errorCode) && exceptionClass(exceptionClass);
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
            case REPETITION_LOOP, CANCELLATION, INTERRUPTION, UNEXPECTED -> code == 500;
        };
    }

    private static boolean canonicalSourcePurpose(Map<String, Object> data) {
        return switch (text(data, "source")) {
            case "FOREGROUND_PROVIDER" -> "TURN_RESPONSE".equals(text(data, "purpose"));
            case "BACKGROUND_PROVIDER" -> "BACKGROUND_MAINTENANCE".equals(text(data, "purpose"));
            case "PLACEHOLDER_RUNTIME" -> "PLACEHOLDER_RESPONSE".equals(text(data, "purpose"));
            case "SCRIPTED_HARNESS" -> "HARNESS_FIXTURE".equals(text(data, "purpose"));
            default -> false;
        };
    }

    private static boolean validGenerationId(String generationId, String traceId) {
        return safeToken(traceId, 128)
                && traceId.startsWith("trc-")
                && generationId != null
                && generationId.length() <= 192
                && generationId.matches(java.util.regex.Pattern.quote(traceId)
                        + "-generation-[1-9][0-9]{0,9}");
    }

    private static boolean booleanKey(String key) {
        return Set.of(
                "requestDispatched", "dispatchedNativeTools", "providerBacked",
                "foreground", "completedNormally", "normalized", "truncated",
                "textualToolCallPresent",
                "nativeToolProposalsTruncated", "argumentNameHashesTruncated",
                "providerTerminalEligible", "providerToolCallEligible", "bound",
                "quarantineRequired", "lateAfterGenerationTerminal",
                "prelaunchNoListener")
                .contains(key) && !"dispatchedNativeTools".equals(key);
    }

    private static boolean validNumber(String key, long value) {
        if (value < 0) return false;
        return switch (key) {
            case "schemaVersion" -> value == 1;
            case "providerLedgerVersion", "transportLedgerVersion" ->
                    value == 1 || value == 2;
            case "turnNumber", "ledgerSequence", "attemptOrdinal",
                    "selectedAttemptOrdinal", "proposalOrdinal", "transportSequence",
                    "processId", "processStartEpochMilli", "launchObservedEpochMilli",
                    "readinessObservedEpochMilli", "managedPort" ->
                    value > 0;
            case "errorCode" -> value <= 599;
            case "runtimeArtifactSizeBytes", "runtimeClasspathSizeBytes",
                    "runtimeClasspathEntryCount", "assistantChars",
                    "nativeToolCallCount", "capturedNativeToolProposalCount",
                    "toolNameChars", "argumentCount" -> true;
            default -> false;
        };
    }

    private static boolean validText(String key, String value) {
        if (value == null || value.length() > 256) return false;
        return switch (key) {
            case "generationId", "attemptId", "selectedAttemptId",
                    "transportOperationId" -> safeToken(value, 256);
            case "processReceiptId" -> value.matches("pr-[0-9a-f]{64}");
            case "runtimeArtifactSha256", "runtimeClasspathSha256" -> bareHashOrBlank(value);
            case "candidateDeclarationSha256", "engineExecutableSha256",
                    "modelArtifactSha256", "launchCommandSha256",
                    "effectiveEnvironmentSha256",
                    "propsResponseSha256", "endpointObserverIdentitySha256" ->
                    bareHash(value);
            case "schemaName" -> ProviderProcessLaunchReceipt.SCHEMA_NAME.equals(value);
            case "launchNonce" -> value.matches("[0-9a-f]{32}");
            case "managedHost" -> "127.0.0.1".equals(value);
            case "endpointObserverKind" -> enumValue(
                    ProviderProcessLaunchReceipt.EndpointObserverKind.class, value) != null;
            case "endpointOwnershipBoundary" -> enumValue(
                    ProviderProcessLaunchReceipt.EndpointOwnershipBoundary.class, value) != null;
            case "stableEndpointAssumption" -> enumValue(
                    ProviderProcessLaunchReceipt.StableEndpointAssumption.class, value) != null;
            case "resolvedProfile" -> value.matches("[a-z0-9][a-z0-9._-]{0,127}");
            case "environmentContract" -> enumValue(
                    ProviderProcessLaunchReceipt.EnvironmentContract.class, value) != null;
            case "launchArgumentSet" -> enumValue(
                    ProviderProcessLaunchReceipt.LaunchArgumentSet.class, value) != null;
            case "servedIdentity" -> enumValue(
                    ProviderProcessLaunchReceipt.ServedIdentity.class, value) != null;
            case "contentVerificationBoundary" -> enumValue(
                    ProviderProcessLaunchReceipt.ContentVerificationBoundary.class, value) != null;
            case "stableFilesystemAssumption" -> enumValue(
                    ProviderProcessLaunchReceipt.StableFilesystemAssumption.class, value) != null;
            case "assistantHash", "providerBodyHash", "providerCallIdHash",
                    "toolNameHash", "argumentsHash", "namedToolNameHash" -> providerHash(value);
            case "source" -> enumValue(ProviderResponseCapture.Source.class, value) != null;
            case "purpose" -> enumValue(ProviderResponseCapture.Purpose.class, value) != null;
            case "toolAuthorityMode" -> enumValue(
                    ProviderResponseCapture.ToolAuthorityMode.class, value) != null;
            case "attemptKind" -> enumValue(ProviderResponseCapture.AttemptKind.class, value) != null;
            case "retryReason" -> enumValue(ProviderResponseCapture.RetryReason.class, value) != null;
            case "completion" -> enumValue(ProviderResponseCapture.Completion.class, value) != null;
            case "failure" -> enumValue(ProviderResponseCapture.Failure.class, value) != null;
            case "abandonment" -> enumValue(ProviderResponseCapture.Abandonment.class, value) != null;
            case "transition" -> enumValue(
                    ProviderTransportAttempt.Transition.class, value) != null;
            case "observationMode" -> enumValue(
                    ProviderTransportAttempt.ObservationMode.class, value) != null;
            case "settlement" -> enumValue(
                    ProviderTransportAttempt.Settlement.class, value) != null;
            case "transportFailure" -> enumValue(
                    ProviderTransportAttempt.Failure.class, value) != null;
            case "cancellationState" -> enumValue(
                    ProviderTransportAttempt.CancellationState.class, value) != null;
            case "cancellationReason" -> enumValue(
                    ProviderTransportAttempt.CancellationReason.class, value) != null;
            case "wireDisposition" -> enumValue(
                    ProviderTransportAttempt.WireDisposition.class, value) != null;
            case "processBinding" -> enumValue(
                    ProviderTransportAttempt.ProcessBinding.class, value) != null;
            case "toolChoiceMode" -> enumValue(ToolChoiceMode.class, value) != null;
            case "toolNameClassification" -> "BLANK".equals(value)
                    || enumValue(ToolAliasPolicy.AliasDecisionStatus.class, value) != null;
            case "settlementKind" -> enumValue(TurnSettlement.Kind.class, value) != null;
            case "stage" -> enumValue(TurnExecutionStage.class, value) != null;
            case "errorKind" -> enumValue(TurnFailureClassifier.ErrorKind.class, value) != null;
            case "causalOwner" -> "UNKNOWN".equals(value);
            case "exceptionClass" -> value.isEmpty() || exceptionClass(value);
            case "redactionMode" -> enumValue(TraceRedactionMode.class, value) != null;
            case "runtimeArtifactKind" -> value.isEmpty()
                    || Set.of("JAR", "EXPLODED_CLASSES", "UNAVAILABLE").contains(value);
            case "runtimeVersion" -> "unknown".equals(value)
                    || value.matches("[0-9][A-Za-z0-9._+\\-]{0,63}");
            case "runtimeCommit" -> "unknown".equals(value)
                    || value.matches("[0-9a-f]{40}");
            case "initialization" -> Set.of(
                    "DEGRADED", "TERMINALIZATION_RECOVERY").contains(value);
            default -> false;
        };
    }

    private static boolean listKey(String key) {
        return Set.of(
                "dispatchedNativeTools", "argumentNameHashes",
                "selectedToolNameHashes", "authorizedToolNameHashes")
                .contains(key);
    }

    private static boolean validListItem(String key, String value) {
        return switch (key) {
            case "dispatchedNativeTools" -> value != null
                    && value.matches("talos\\.[a-z0-9][a-z0-9._-]{0,127}");
            case "argumentNameHashes", "selectedToolNameHashes",
                    "authorizedToolNameHashes" -> providerHash(value);
            default -> false;
        };
    }

    private static boolean bareHashOrBlank(String value) {
        return value != null && (value.isEmpty() || value.matches("[0-9a-f]{64}"));
    }

    private static boolean bareHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean providerHash(String value) {
        return value != null
                && value.matches("sha256:(?:[0-9a-f]{64}|unavailable)");
    }

    private static boolean exceptionClass(String value) {
        return value != null && value.length() <= 256
                && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof String text ? text : "";
    }

    private static long number(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                ? ((Number) value).longValue()
                : Long.MIN_VALUE;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static EventSpec spec(String type) {
        return switch (type) {
            case "TRACE_STARTED" -> new EventSpec(
                    Set.of("turnNumber", "redactionMode"), TRACE_STARTED);
            case "MODEL_IDENTITY_RESOLVED" -> new EventSpec(
                    ModelIdentitySnapshot.PROCESS_BINDING_TRACE_FIELDS,
                    ModelIdentitySnapshot.TRACE_FIELDS);
            case "TRACE_COMPLETED" -> new EventSpec(Set.of(), Set.of());
            case "TURN_SETTLED" -> new EventSpec(SETTLEMENT, SETTLEMENT);
            case "TURN_TERMINALIZATION_RECOVERED" ->
                    new EventSpec(RECOVERY, RECOVERY);
            case "GENERATION_STARTED" -> new EventSpec(GENERATION, GENERATION);
            case "GENERATION_ATTEMPT_STARTED", "GENERATION_TRANSPORT_UNOBSERVED" ->
                    new EventSpec(ATTEMPT, ATTEMPT);
            case "GENERATION_REQUEST_DISPATCHED" -> new EventSpec(
                    union(ATTEMPT, Set.of("toolAuthoritySurface")),
                    union(ATTEMPT, Set.of(
                            "providerBodyHash", "dispatchedNativeTools",
                            "toolAuthoritySurface", "transportOperationId",
                            "processReceiptId")));
            case "GENERATION_RESPONSE_CAPTURED" -> new EventSpec(
                    RESPONSE,
                    union(RESPONSE, Set.of("transportOperationId", "processReceiptId")));
            case "GENERATION_ATTEMPT_FAILED" -> new EventSpec(
                    union(ATTEMPT, Set.of("failure")),
                    union(ATTEMPT, Set.of(
                            "failure", "transportOperationId", "processReceiptId")));
            case "GENERATION_ATTEMPT_ABANDONED" -> new EventSpec(
                    union(ATTEMPT, Set.of("abandonment")),
                    union(ATTEMPT, Set.of(
                            "abandonment", "transportOperationId", "processReceiptId")));
            case "GENERATION_FAILED" -> new EventSpec(
                    union(GENERATION, Set.of("failure")),
                    union(GENERATION, Set.of(
                            "failure", "transportOperationId", "processReceiptId")));
            case "GENERATION_ABANDONED" -> new EventSpec(
                    union(GENERATION, Set.of("abandonment")),
                    union(GENERATION, Set.of(
                            "abandonment", "transportOperationId", "processReceiptId")));
            case "GENERATION_COMPLETED" -> new EventSpec(
                    union(GENERATION, Set.of(
                            "selectedAttemptId", "selectedAttemptOrdinal", "completion",
                            "assistantHash", "providerTerminalEligible",
                            "providerToolCallEligible")),
                    union(GENERATION, Set.of(
                            "selectedAttemptId", "selectedAttemptOrdinal", "completion",
                            "assistantHash", "providerTerminalEligible",
                            "providerToolCallEligible", "transportOperationId",
                            "processReceiptId")));
            case "PROVIDER_TOOL_PROPOSAL_CAPTURED" ->
                    new EventSpec(PROPOSAL,
                            union(PROPOSAL, Set.of(
                                    "transportOperationId", "processReceiptId")));
            case "PROVIDER_TRANSPORT_LIFECYCLE" ->
                    new EventSpec(TRANSPORT, PROCESS_BOUND_TRANSPORT);
            case "PROVIDER_PROCESS_LAUNCH_RECEIPT" ->
                    new EventSpec(PROCESS_RECEIPT, PROCESS_RECEIPT);
            default -> null;
        };
    }

    private static boolean providerEvent(String type) {
        return type != null && (type.startsWith("GENERATION_")
                || type.startsWith("PROVIDER_"));
    }

    private static String safeTimestamp(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > 64) return "";
        try {
            Instant.parse(value);
            return value;
        } catch (DateTimeParseException invalid) {
            return "";
        }
    }

    private static boolean safeToken(String value, int maxChars) {
        return value != null
                && value.length() <= maxChars
                && value.matches("[A-Za-z0-9._:$+\\-]*");
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    private record EventSpec(Set<String> required, Set<String> allowed) {}
}
