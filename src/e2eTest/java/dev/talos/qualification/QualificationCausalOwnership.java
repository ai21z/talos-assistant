package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical causal-ownership gate for qualification result schema v2. */
final class QualificationCausalOwnership {
    private static final Set<String> ANSWER_TRANSFORMATION_REASON_CODES = Set.of(
            "EXECUTOR_POLICY_SHAPING",
            "PROVIDER_RESPONSE_NORMALIZATION",
            "TASK_CONTRACT_TOOL_CALL_SUPPRESSION",
            "MODE_PRESENTATION_LAYOUT",
            "RETRIEVAL_CITATION_SUFFIX",
            "SESSION_COMMIT_NORMALIZATION",
            "TOOL_LOOP_ITERATION_LIMIT",
            "TOOL_LOOP_FINALIZATION");
    private static final Set<String> ANSWER_RESTORATION_REASON_CODES = Set.of(
            "SPECULATIVE_RETRY_REJECTED",
            "SPECULATIVE_RETRY_FAILED",
            "SPECULATIVE_RETRY_EMPTY");

    private QualificationCausalOwnership() {}

    record Evaluation(List<String> structuralReasonCodes, List<String> evidenceReasonCodes) {
        Evaluation {
            structuralReasonCodes = immutableDistinct(structuralReasonCodes);
            evidenceReasonCodes = immutableDistinct(evidenceReasonCodes);
        }

        boolean structurallyComplete() {
            return structuralReasonCodes.isEmpty();
        }

        boolean complete() {
            return structurallyComplete() && evidenceReasonCodes.isEmpty();
        }

        List<String> reasonCodes() {
            ArrayList<String> reasons = new ArrayList<>(structuralReasonCodes);
            reasons.addAll(evidenceReasonCodes);
            return List.copyOf(reasons);
        }
    }

    static Evaluation evaluateStructural(QualificationRecords.RunResult result) {
        return evaluate(result, true, false);
    }

    static Evaluation evaluate(
            QualificationRecords.RunResult result,
            boolean modelAnswerEvidenceMatched) {
        return evaluate(result, modelAnswerEvidenceMatched, true);
    }

    private static Evaluation evaluate(
            QualificationRecords.RunResult result,
            boolean modelAnswerEvidenceMatched,
            boolean requireArtifactProof) {
        Objects.requireNonNull(result, "result");
        List<String> structural = new ArrayList<>();
        List<String> evidence = new ArrayList<>();

        if (result.schemaVersion() == QualificationRecords.LEGACY_RUN_SCHEMA_VERSION) {
            structural.add("LEGACY_CAUSAL_SCHEMA_V1");
            return new Evaluation(structural, evidence);
        }
        if (result.schemaVersion() != QualificationRecords.RUN_SCHEMA_VERSION) {
            structural.add("UNSUPPORTED_CAUSAL_SCHEMA_VERSION");
            return new Evaluation(structural, evidence);
        }

        QualificationRecords.CausalOwner owner = result.causalOwner();
        boolean modelDecisionFailureProven = result.causalLedger().stream().anyMatch(entry ->
                entry.firstCause()
                        && entry.owner() == QualificationRecords.CausalOwner.MODEL
                        && entry.stage() == QualificationRecords.CausalStage.DECISION
                        && entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_FAILED);
        if (owner == QualificationRecords.CausalOwner.UNKNOWN) {
            structural.add("CAUSAL_OWNER_UNKNOWN");
        } else if (owner == QualificationRecords.CausalOwner.MIXED) {
            structural.add("CAUSAL_OWNER_MIXED");
        }
        if ((owner == QualificationRecords.CausalOwner.UNKNOWN
                || owner == QualificationRecords.CausalOwner.MIXED)
                && result.comparativeScoreEligible()) {
            structural.add("UNRESOLVED_CAUSE_MARKED_SCORE_ELIGIBLE");
        }
        if (result.evidenceComplete()
                != result.trustGates().mandatoryEvidencePresent()) {
            structural.add("EVIDENCE_COMPLETE_MANDATORY_GATE_MISMATCH");
        }
        if (!result.evidenceComplete() && result.comparativeScoreEligible()) {
            structural.add("INCOMPLETE_EVIDENCE_MARKED_SCORE_ELIGIBLE");
        }
        boolean expectedScoreEligibility = result.evidenceComplete()
                && (result.failureClass() == QualificationRecords.FailureClass.MODEL_FAILURE
                        || (result.failureClass()
                                == QualificationRecords.FailureClass.OPERATOR_INTERRUPTION
                            && result.responseOwnerContract()
                                == QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED)
                        || (result.failureClass() == QualificationRecords.FailureClass.NONE
                                && result.answerOrigin()
                                        != QualificationRecords.AnswerOrigin.NONE
                                && QualificationCausalFinalizer.responseOwnerContractSatisfied(
                                        result.responseOwnerContract(), result.answerOrigin())));
        if (result.comparativeScoreEligible() != expectedScoreEligibility) {
            structural.add("COMPARATIVE_SCORE_ELIGIBILITY_MISMATCH");
        }
        if (result.causalLedger().stream()
                .anyMatch(entry -> entry.owner() == QualificationRecords.CausalOwner.UNKNOWN)) {
            structural.add("CAUSAL_LEDGER_OWNER_UNKNOWN");
        }
        if (result.causalLedger().stream()
                .anyMatch(entry -> entry.owner() == QualificationRecords.CausalOwner.MIXED)) {
            structural.add("CAUSAL_LEDGER_OWNER_MIXED");
        }

        List<QualificationRecords.CausalLedgerEntry> firstCauses = result.causalLedger().stream()
                .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                .toList();
        if (result.failureClass() == QualificationRecords.FailureClass.NONE) {
            if (!firstCauses.isEmpty()) structural.add("FIRST_CAUSE_PRESENT_WITHOUT_FAILURE");
        } else if (owner == QualificationRecords.CausalOwner.UNKNOWN) {
            if (!firstCauses.isEmpty()) structural.add("UNKNOWN_CAUSE_HAS_FIRST_CAUSE");
        } else if (owner == QualificationRecords.CausalOwner.MIXED) {
            long distinctOwners = firstCauses.stream()
                    .map(QualificationRecords.CausalLedgerEntry::owner)
                    .filter(firstOwner -> firstOwner != QualificationRecords.CausalOwner.MIXED
                            && firstOwner != QualificationRecords.CausalOwner.UNKNOWN)
                    .distinct()
                    .count();
            if (firstCauses.size() < 2 || distinctOwners < 2) {
                structural.add("MIXED_CAUSE_LACKS_CONTRIBUTING_FIRST_CAUSES");
            }
            if (result.causalReasonCode()
                    != QualificationRecords.CausalReason.MULTIPLE_CONTRIBUTING_OWNERS) {
                structural.add("MIXED_CAUSE_REASON_MISMATCH");
            }
        } else {
            if (result.causalLedger().isEmpty()) structural.add("CAUSAL_LEDGER_EMPTY_FOR_FAILURE");
            if (firstCauses.size() != 1) {
                structural.add("CAUSAL_FIRST_CAUSE_COUNT_" + firstCauses.size());
            } else if (firstCauses.getFirst().owner() != owner) {
                structural.add("CAUSAL_FIRST_CAUSE_OWNER_MISMATCH");
            } else if (firstCauses.getFirst().reasonCode() != result.causalReasonCode()) {
                structural.add("CAUSAL_REASON_CODE_MISMATCH");
            }
        }

        switch (result.failureClass()) {
            case NONE -> {
                QualificationRecords.CausalOwner expectedOwner = switch (result.answerOrigin()) {
                    case MODEL_PROVIDER -> QualificationRecords.CausalOwner.MODEL;
                    case RUNTIME_DETERMINISTIC, TOOL_DETERMINISTIC ->
                            QualificationRecords.CausalOwner.RUNTIME_POLICY;
                    case OPERATOR -> QualificationRecords.CausalOwner.OPERATOR;
                    case NONE -> QualificationRecords.CausalOwner.UNKNOWN;
                };
                if (result.answerOrigin() == QualificationRecords.AnswerOrigin.NONE
                        || owner != expectedOwner
                        || !QualificationCausalFinalizer.responseOwnerContractSatisfied(
                                result.responseOwnerContract(), result.answerOrigin())) {
                    structural.add("SUCCESS_CAUSAL_OWNER_MISMATCH");
                }
            }
            case INCOMPLETE_EVIDENCE -> {
                if (owner != QualificationRecords.CausalOwner.PROVIDER
                        && owner != QualificationRecords.CausalOwner.HARNESS
                        && owner != QualificationRecords.CausalOwner.MIXED
                        && owner != QualificationRecords.CausalOwner.UNKNOWN) {
                    structural.add("INCOMPLETE_EVIDENCE_CAUSAL_OWNER_MISMATCH");
                }
            }
            case MODEL_FAILURE -> {
                if (result.answerOrigin() != QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                        && !modelDecisionFailureProven) {
                    structural.add("MODEL_FAILURE_ANSWER_NOT_MODEL_PROVIDER");
                }
                if (!modelDecisionFailureProven
                        && result.responseOwnerContract()
                            != QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED) {
                    structural.add("MODEL_FAILURE_RESPONSE_OWNER_CONTRACT_MISMATCH");
                }
                if (owner != QualificationRecords.CausalOwner.MODEL) {
                    structural.add("MODEL_FAILURE_CAUSAL_OWNER_NOT_MODEL");
                }
                if (requireArtifactProof && !modelAnswerEvidenceMatched
                        && !modelDecisionFailureProven) {
                    evidence.add("MODEL_FAILURE_LACKS_MATCHED_MODEL_RESPONSE");
                }
            }
            case TALOS_RUNTIME_DEFECT -> {
                if (owner != QualificationRecords.CausalOwner.RUNTIME_POLICY
                        && owner != QualificationRecords.CausalOwner.VERIFIER) {
                    structural.add("RUNTIME_DEFECT_CAUSAL_OWNER_MISMATCH");
                }
            }
            case HARDWARE_FAILURE -> {
                if (owner != QualificationRecords.CausalOwner.HARDWARE) {
                    structural.add("HARDWARE_FAILURE_CAUSAL_OWNER_MISMATCH");
                }
            }
            case OPERATOR_INTERRUPTION -> {
                if (owner != QualificationRecords.CausalOwner.OPERATOR
                        || result.answerOrigin() != QualificationRecords.AnswerOrigin.OPERATOR
                        || result.responseOwnerContract()
                            != QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED) {
                    structural.add("OPERATOR_INTERRUPTION_OWNER_MISMATCH");
                }
            }
        }
        if (owner == QualificationRecords.CausalOwner.HARNESS
                && result.failureClass()
                        != QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE) {
            structural.add("HARNESS_OWNER_REQUIRES_INCOMPLETE_EVIDENCE");
        }

        if (requireArtifactProof
                && result.failureClass() != QualificationRecords.FailureClass.MODEL_FAILURE
                && result.answerOrigin() == QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                && !modelAnswerEvidenceMatched) {
            evidence.add("ANSWER_ORIGIN_MODEL_PROVIDER_UNPROVEN");
        }
        return new Evaluation(structural, evidence);
    }

    /** Matches result evidence under its own historical or current schema contract. */
    static boolean modelAnswerEvidenceMatched(Path resultPath) {
        if (resultPath == null) return false;
        try {
            Path absolute = resultPath.toAbsolutePath().normalize();
            QualificationRecords.RunResult result = QualificationJson.read(
                    NoFollowPathPolicy.readAllBytes(absolute),
                    QualificationRecords.RunResult.class);
            int protocolVersion = result.schemaVersion()
                    == QualificationRecords.LEGACY_RUN_SCHEMA_VERSION
                    ? 5 : QualificationProtocol.VERSION;
            return matchedAnswerOriginForRun(absolute.getParent(), protocolVersion)
                    == QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    static boolean modelAnswerEvidenceMatchedForRun(Path runRoot) {
        return matchedAnswerOriginForRun(runRoot)
                == QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
    }

    static boolean deterministicAnswerEvidenceMatchedForRun(Path runRoot) {
        return matchedAnswerOriginForRun(runRoot)
                == QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC;
    }

    static QualificationRecords.AnswerOrigin matchedAnswerOriginForRun(Path runRoot) {
        if (runRoot == null) return QualificationRecords.AnswerOrigin.NONE;
        Path resultPath = runRoot.toAbsolutePath().normalize().resolve("result.json");
        try {
            NoFollowPathPolicy.GuardedRoot runFiles =
                    NoFollowPathPolicy.bindExistingDirectory(runRoot);
            if (runFiles.inspect(resultPath)
                    != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                return QualificationRecords.AnswerOrigin.NONE;
            }
            QualificationRecords.RunResult result = QualificationJson.read(
                    runFiles.readAllBytes(resultPath),
                    QualificationRecords.RunResult.class);
            int protocolVersion = result.schemaVersion()
                    == QualificationRecords.LEGACY_RUN_SCHEMA_VERSION
                    ? 5 : QualificationProtocol.VERSION;
            return matchedAnswerOriginForRun(runRoot, protocolVersion);
        } catch (IOException | RuntimeException failure) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
    }

    private static QualificationRecords.AnswerOrigin matchedAnswerOriginForRun(
            Path runRoot,
            int protocolVersion) {
        if (runRoot == null) return QualificationRecords.AnswerOrigin.NONE;
        runRoot = runRoot.toAbsolutePath().normalize();
        Path answerPath = runRoot.resolve("answer.txt");
        Path tracePath = runRoot.resolve("trace.json");
        try {
            NoFollowPathPolicy.GuardedRoot runFiles =
                    NoFollowPathPolicy.bindExistingDirectory(runRoot);
            if (runFiles.inspect(answerPath)
                            != NoFollowPathPolicy.EntryType.REGULAR_FILE
                    || runFiles.inspect(tracePath)
                            != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                return QualificationRecords.AnswerOrigin.NONE;
            }
            return matchedAnswerOrigin(
                    runFiles.readAllBytes(answerPath),
                    runFiles.readAllBytes(tracePath),
                    protocolVersion);
        } catch (IOException | RuntimeException failure) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
    }

    static QualificationRecords.AnswerOrigin matchedAnswerOrigin(
            byte[] answerBytes,
            byte[] traceBytes) {
        return matchedAnswerOrigin(answerBytes, traceBytes, QualificationProtocol.VERSION);
    }

    static QualificationRecords.AnswerOrigin matchedAnswerOrigin(
            byte[] answerBytes,
            byte[] traceBytes,
            int protocolVersion) {
        if (answerBytes == null || traceBytes == null) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        try {
            if (protocolVersion >= 1 && protocolVersion <= 5) {
                return matchedLegacyAnswerOrigin(answerBytes, traceBytes);
            }
            if (protocolVersion != QualificationProtocol.VERSION) {
                return QualificationRecords.AnswerOrigin.NONE;
            }
            return matchedProtocolV6AnswerOrigin(answerBytes, traceBytes);
        } catch (IOException | RuntimeException failure) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
    }

    private static QualificationRecords.AnswerOrigin matchedProtocolV6AnswerOrigin(
            byte[] answerBytes,
            byte[] traceBytes) throws IOException {
        List<AnswerFingerprint> expected = answerFingerprints(answerBytes);
        JsonNode root = QualificationJson.readTree(traceBytes);
        if (!root.isObject()
                || exactInt(root.path("schemaVersion"))
                        != QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        JsonNode events = root.path("events");
        if (!events.isArray()) return QualificationRecords.AnswerOrigin.NONE;
        QualificationProviderAttemptLedger.Ledger ledger =
                QualificationProviderAttemptLedger.parse(root);
        if (!allRestorationsBound(events, ledger)) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        SelectionChain chain = selectionChain(events, expected);
        if (chain == null) return QualificationRecords.AnswerOrigin.NONE;

        if (hasInvalidatingEventAfter(events, chain.terminalIndex())) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        if (chain.origin() == QualificationRecords.AnswerOrigin.NONE) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        if (chain.origin() == QualificationRecords.AnswerOrigin.MODEL_PROVIDER) {
            QualificationProviderAttemptLedger.Generation generation =
                    ledger.selectedGeneration(
                            chain.sourceGenerationId(), chain.sourceAttemptId());
            QualificationProviderAttemptLedger.Response response = generation == null
                    ? null : generation.selectedResponse();
            AnswerRestoration restoration = lastRestorationBefore(
                    events, chain.terminalIndex());
            if (restoration != null
                    && (restoration.origin()
                                    != QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                            || !restoration.sourceGenerationId().equals(
                                    chain.sourceGenerationId())
                            || !restoration.sourceAttemptId().equals(
                                    chain.sourceAttemptId()))) {
                return QualificationRecords.AnswerOrigin.NONE;
            }
            int providerLineageEnd = restoration == null
                    ? chain.terminalIndex()
                    : restoration.eventIndex();
            String providerLineageHash = restoration == null
                    ? chain.answerHash()
                    : restoration.answerHash();
            int providerLineageChars = restoration == null
                    ? chain.answerChars()
                    : restoration.answerChars();
            ProviderToolSuppression suppression = response == null
                    ? ProviderToolSuppression.absent()
                    : providerToolSuppression(
                            events,
                            generation.terminalEventIndex(),
                            providerLineageEnd,
                            chain.sourceGenerationId(),
                            chain.sourceAttemptId(),
                            response);
            if (response == null
                    || !suppression.valid()
                    || response.toolCallPresent() != suppression.present()
                    || !generation.providerTerminalEligible()
                    || generation.terminalEventIndex() >= chain.terminalIndex()
                    || response.source()
                            != dev.talos.spi.types.ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                    || response.purpose()
                            != dev.talos.spi.types.ProviderResponseCapture.Purpose.TURN_RESPONSE
                    || response.completion()
                            != dev.talos.spi.types.ProviderResponseCapture.Completion.PROVIDER_EOS
                    || !response.providerBacked()
                    || !response.foreground()
                    || !response.completedNormally()
                    || response.normalized()
                    || response.truncated()
                    || (restoration == null
                            ? ledger.latestNonBackgroundEventIndexBefore(
                                    chain.terminalIndex())
                                    != generation.terminalEventIndex()
                            : ledger.latestNonBackgroundEventIndexBefore(
                                    chain.terminalIndex())
                                    >= restoration.eventIndex())
                    || hasEventBetween(events, "DETERMINISTIC_ANSWER_SELECTED",
                            restoration == null
                                    ? generation.terminalEventIndex()
                                    : restoration.eventIndex(),
                            chain.terminalIndex())
                    || !answerLineageMatches(
                            events,
                            generation.terminalEventIndex(),
                            providerLineageEnd,
                            response.assistantHash(),
                            response.assistantChars(),
                            providerLineageHash,
                            providerLineageChars)
                    || (restoration != null && !answerLineageMatches(
                            events,
                            restoration.eventIndex(),
                            chain.terminalIndex(),
                            restoration.answerHash(),
                            restoration.answerChars(),
                            chain.answerHash(),
                            chain.answerChars()))) {
                return QualificationRecords.AnswerOrigin.NONE;
            }
            return QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
        }

        AnswerRestoration restoration = lastRestorationBefore(
                events, chain.terminalIndex());
        if (restoration != null
                && (restoration.origin() != chain.origin()
                        || !restoration.sourceGenerationId().isBlank()
                        || !restoration.sourceAttemptId().isBlank())) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        Selection selected = restoration == null
                ? lastDeterministicSelectionBefore(events, chain.terminalIndex())
                : matchingDeterministicSelectionBefore(events, restoration);
        if (selected == null
                || selected.origin() != chain.origin()
                || (restoration == null
                        ? ledger.latestNonBackgroundEventIndexBefore(chain.terminalIndex())
                                > selected.eventIndex()
                        : ledger.latestNonBackgroundEventIndexBefore(chain.terminalIndex())
                                >= restoration.eventIndex())
                || !answerLineageMatches(
                        events,
                        selected.eventIndex(),
                        restoration == null
                                ? chain.terminalIndex()
                                : restoration.eventIndex(),
                        selected.answerHash(),
                        selected.answerChars(),
                        restoration == null
                                ? chain.answerHash()
                                : restoration.answerHash(),
                        restoration == null
                                ? chain.answerChars()
                                : restoration.answerChars())
                || (restoration != null && !answerLineageMatches(
                        events,
                        restoration.eventIndex(),
                        chain.terminalIndex(),
                        restoration.answerHash(),
                        restoration.answerChars(),
                        chain.answerHash(),
                        chain.answerChars()))) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        return chain.origin();
    }

    private static QualificationRecords.AnswerOrigin matchedLegacyAnswerOrigin(
            byte[] answerBytes,
            byte[] traceBytes) throws IOException {
        List<AnswerFingerprint> expected = answerFingerprints(answerBytes);
        JsonNode root = QualificationJson.readTree(traceBytes);
        if (!root.isObject() || exactInt(root.path("schemaVersion")) != 2) {
            return QualificationRecords.AnswerOrigin.NONE;
        }
        JsonNode events = root.path("events");
        if (!events.isArray()) return QualificationRecords.AnswerOrigin.NONE;
        QualificationRecords.AnswerOrigin lastOrigin = QualificationRecords.AnswerOrigin.NONE;
        String lastHash = "";
        int lastChars = -1;
        for (JsonNode event : events) {
            String type = event.path("type").asText("");
            if ("OUTCOME_RENDERED".equals(type)) {
                return matches(expected, lastHash, lastChars)
                        ? lastOrigin : QualificationRecords.AnswerOrigin.NONE;
            }
            JsonNode data = event.path("data");
            if ("MODEL_RESPONSE_RECEIVED".equals(type)) {
                lastOrigin = QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
                lastHash = data.path("assistantHash").asText("");
                lastChars = optionalExactInt(data.path("assistantChars"));
            } else if ("PROVIDER_GENERATION_RECEIVED".equals(type)) {
                if (data.path("providerBacked").asBoolean(false)
                        && data.path("foreground").asBoolean(false)
                        && data.path("completedNormally").asBoolean(false)) {
                    lastOrigin = QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
                    lastHash = data.path("assistantHash").asText("");
                    lastChars = optionalExactInt(data.path("assistantChars"));
                } else {
                    lastOrigin = QualificationRecords.AnswerOrigin.NONE;
                    lastHash = "";
                    lastChars = -1;
                }
            } else if ("DETERMINISTIC_ANSWER_SELECTED".equals(type)) {
                String origin = data.path("origin").asText(
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC.name());
                try {
                    lastOrigin = QualificationRecords.AnswerOrigin.valueOf(origin);
                } catch (IllegalArgumentException invalid) {
                    lastOrigin = QualificationRecords.AnswerOrigin.NONE;
                }
                lastHash = data.path("answerHash").asText("");
                lastChars = optionalExactInt(data.path("answerChars"));
            }
        }
        return QualificationRecords.AnswerOrigin.NONE;
    }

    private static SelectionChain selectionChain(
            JsonNode events,
            List<AnswerFingerprint> expected) {
        int terminalIndex = -1;
        int routedIndex = -1;
        int outcomeIndex = -1;
        int completeIndex = -1;
        JsonNode terminalData = null;
        JsonNode routedData = null;
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            String type = event.path("type").asText("");
            JsonNode data = event.path("data");
            switch (type) {
                case "DETERMINISTIC_ANSWER_SELECTED" -> {
                    if (deterministicSelection(event, index) == null) return null;
                }
                case "ANSWER_TRANSFORMED" -> {
                    if (answerTransformation(event, index) == null) return null;
                }
                case "ANSWER_CANDIDATE_RESTORED" -> {
                    if (answerRestoration(event, index) == null) return null;
                }
                case "TERMINAL_ANSWER_SELECTED" -> {
                    if (terminalIndex >= 0) return null;
                    terminalIndex = index;
                    terminalData = data;
                }
                case "ROUTED_ANSWER_CAPTURED" -> {
                    if (routedIndex >= 0 || !exactKeys(data, Set.of(
                            "answerHash", "answerChars"))) return null;
                    routedIndex = index;
                    routedData = data;
                }
                case "OUTCOME_RENDERED" -> {
                    if (outcomeIndex >= 0 || !exactKeys(data, Set.of(
                            "status", "classification"))
                            || !data.path("status").isTextual()
                            || !data.path("classification").isTextual()) return null;
                    outcomeIndex = index;
                }
                case "TRACE_COMPLETED" -> {
                    if (completeIndex >= 0 || !exactKeys(data, Set.of())) return null;
                    completeIndex = index;
                }
                default -> {
                    // Other trace evidence is orthogonal to the terminal provenance chain.
                }
            }
        }
        if (terminalIndex < 0 || routedIndex != terminalIndex + 1
                || outcomeIndex <= routedIndex || completeIndex != outcomeIndex + 1
                || completeIndex != events.size() - 1 || terminalData == null
                || routedData == null
                || !onlyEventTypesBetween(
                        events, routedIndex, outcomeIndex, Set.of("VERIFICATION_COMPLETED"))) {
            return null;
        }

        if (!terminalData.path("origin").isTextual()) return null;
        QualificationRecords.AnswerOrigin origin;
        try {
            origin = QualificationRecords.AnswerOrigin.valueOf(
                    terminalData.path("origin").textValue());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        Set<String> terminalKeys = origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                ? Set.of("answerHash", "answerChars", "origin",
                        "sourceGenerationId", "sourceAttemptId")
                : Set.of("answerHash", "answerChars", "origin");
        if (!exactKeys(terminalData, terminalKeys)) return null;
        String answerHash = exactHash(terminalData.path("answerHash"));
        int answerChars = exactNonNegativeInt(terminalData.path("answerChars"));
        if (answerHash == null || answerChars < 0
                || !matches(expected, answerHash, answerChars)
                || !answerHash.equals(exactHash(routedData.path("answerHash")))
                || answerChars != exactNonNegativeInt(routedData.path("answerChars"))) {
            return null;
        }
        String generationId = "";
        String attemptId = "";
        if (origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER) {
            generationId = exactId(terminalData.path("sourceGenerationId"));
            attemptId = exactId(terminalData.path("sourceAttemptId"));
            if (generationId == null || attemptId == null) return null;
        }
        return new SelectionChain(
                terminalIndex, origin, answerHash, answerChars,
                generationId, attemptId);
    }

    private static Selection lastDeterministicSelectionBefore(
            JsonNode events,
            int terminalIndex) {
        Selection selected = null;
        for (int index = 0; index < terminalIndex; index++) {
            if ("DETERMINISTIC_ANSWER_SELECTED".equals(
                    events.get(index).path("type").asText(""))) {
                selected = deterministicSelection(events.get(index), index);
                if (selected == null) return null;
            }
        }
        return selected;
    }

    private static Selection matchingDeterministicSelectionBefore(
            JsonNode events,
            AnswerRestoration restoration) {
        Selection matched = null;
        for (int index = 0; index < restoration.eventIndex(); index++) {
            if (!"DETERMINISTIC_ANSWER_SELECTED".equals(
                    events.get(index).path("type").asText(""))) {
                continue;
            }
            Selection candidate = deterministicSelection(events.get(index), index);
            if (candidate == null) return null;
            if (candidate.origin() == restoration.origin()
                    && answerLineageMatches(
                            events,
                            candidate.eventIndex(),
                            restoration.eventIndex(),
                            candidate.answerHash(),
                            candidate.answerChars(),
                            restoration.answerHash(),
                            restoration.answerChars())) {
                matched = candidate;
            }
        }
        return matched;
    }

    private static AnswerRestoration lastRestorationBefore(
            JsonNode events,
            int exclusiveEnd) {
        AnswerRestoration restored = null;
        for (int index = 0; index < exclusiveEnd; index++) {
            if (!"ANSWER_CANDIDATE_RESTORED".equals(
                    events.get(index).path("type").asText(""))) {
                continue;
            }
            restored = answerRestoration(events.get(index), index);
            if (restored == null) return null;
        }
        return restored;
    }

    private static boolean allRestorationsBound(
            JsonNode events,
            QualificationProviderAttemptLedger.Ledger ledger) {
        for (int index = 0; index < events.size(); index++) {
            if (!"ANSWER_CANDIDATE_RESTORED".equals(
                    events.get(index).path("type").asText(""))) {
                continue;
            }
            AnswerRestoration restoration = answerRestoration(events.get(index), index);
            if (restoration == null) return false;
            if (restoration.origin() == QualificationRecords.AnswerOrigin.MODEL_PROVIDER) {
                QualificationProviderAttemptLedger.Generation generation =
                        ledger.selectedGeneration(
                                restoration.sourceGenerationId(),
                                restoration.sourceAttemptId());
                QualificationProviderAttemptLedger.Response response = generation == null
                        ? null : generation.selectedResponse();
                if (response == null
                        || !generation.providerTerminalEligible()
                        || generation.terminalEventIndex() >= restoration.eventIndex()
                        || response.source()
                                != dev.talos.spi.types.ProviderResponseCapture.Source
                                        .FOREGROUND_PROVIDER
                        || response.purpose()
                                != dev.talos.spi.types.ProviderResponseCapture.Purpose
                                        .TURN_RESPONSE
                        || response.completion()
                                != dev.talos.spi.types.ProviderResponseCapture.Completion
                                        .PROVIDER_EOS
                        || !response.providerBacked()
                        || !response.foreground()
                        || !response.completedNormally()
                        || response.normalized()
                        || response.truncated()
                        || !answerLineageMatches(
                                events,
                                generation.terminalEventIndex(),
                                restoration.eventIndex(),
                                response.assistantHash(),
                                response.assistantChars(),
                                restoration.answerHash(),
                                restoration.answerChars())) {
                    return false;
                }
            } else if (matchingDeterministicSelectionBefore(events, restoration) == null) {
                return false;
            }
        }
        return true;
    }

    private static Selection deterministicSelection(JsonNode event, int eventIndex) {
        JsonNode data = event.path("data");
        if (!exactKeys(data, Set.of("answerHash", "answerChars", "origin"))
                || !data.path("origin").isTextual()) return null;
        QualificationRecords.AnswerOrigin origin;
        try {
            origin = QualificationRecords.AnswerOrigin.valueOf(data.path("origin").textValue());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (origin != QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC
                && origin != QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC
                && origin != QualificationRecords.AnswerOrigin.OPERATOR) return null;
        String hash = exactHash(data.path("answerHash"));
        int chars = exactNonNegativeInt(data.path("answerChars"));
        return hash == null || chars < 0 ? null
                : new Selection(eventIndex, origin, hash, chars);
    }

    private static boolean hasInvalidatingEventAfter(JsonNode events, int terminalIndex) {
        for (int index = terminalIndex + 1; index < events.size(); index++) {
            String type = events.get(index).path("type").asText("");
            if (type.startsWith("GENERATION_")
                    || type.startsWith("TOOL_")
                    || type.startsWith("APPROVAL_")
                    || "PROVIDER_TOOL_PROPOSAL_CAPTURED".equals(type)
                    || "PROVIDER_TOOL_CALLS_SUPPRESSED".equals(type)
                    || "DETERMINISTIC_ANSWER_SELECTED".equals(type)
                    || "ANSWER_TRANSFORMED".equals(type)
                    || "ANSWER_CANDIDATE_RESTORED".equals(type)
                    || "TERMINAL_ANSWER_SELECTED".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static ProviderToolSuppression providerToolSuppression(
            JsonNode events,
            int generationTerminalIndex,
            int terminalAnswerIndex,
            String generationId,
            String attemptId,
            QualificationProviderAttemptLedger.Response response) {
        int matches = 0;
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            if (!"PROVIDER_TOOL_CALLS_SUPPRESSED".equals(
                    event.path("type").asText(""))) {
                continue;
            }
            if (index <= generationTerminalIndex || index >= terminalAnswerIndex) {
                return ProviderToolSuppression.invalid();
            }
            JsonNode data = event.path("data");
            if (!exactKeys(data, Set.of(
                    "sourceGenerationId", "sourceAttemptId", "reasonCode",
                    "nativeToolCallCount", "textualToolCallPresent"))
                    || !generationId.equals(exactId(data.path("sourceGenerationId")))
                    || !attemptId.equals(exactId(data.path("sourceAttemptId")))
                    || !"TASK_CONTRACT_BLOCKED_TOOL_CALLS".equals(
                            data.path("reasonCode").asText(""))
                    || exactNonNegativeInt(data.path("nativeToolCallCount"))
                            != response.nativeToolCallCount()
                    || !data.path("textualToolCallPresent").isBoolean()
                    || data.path("textualToolCallPresent").booleanValue()
                            != response.textualToolCallPresent()) {
                return ProviderToolSuppression.invalid();
            }
            matches++;
        }
        if (matches > 1) return ProviderToolSuppression.invalid();
        if (matches == 1 && hasToolLifecycleEventBetween(
                events, generationTerminalIndex, terminalAnswerIndex)) {
            return ProviderToolSuppression.invalid();
        }
        return new ProviderToolSuppression(matches == 1, true);
    }

    private static boolean hasToolLifecycleEventBetween(
            JsonNode events,
            int exclusiveStart,
            int exclusiveEnd) {
        for (int index = exclusiveStart + 1; index < exclusiveEnd; index++) {
            String type = events.get(index).path("type").asText("");
            if (type.startsWith("TOOL_CALL_")
                    || "TOOL_EXECUTED".equals(type)
                    || type.startsWith("APPROVAL_")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEventBetween(
            JsonNode events,
            String type,
            int exclusiveStart,
            int exclusiveEnd) {
        for (int index = exclusiveStart + 1; index < exclusiveEnd; index++) {
            if (type.equals(events.get(index).path("type").asText(""))) return true;
        }
        return false;
    }

    private static boolean onlyEventTypesBetween(
            JsonNode events,
            int exclusiveStart,
            int exclusiveEnd,
            Set<String> allowedTypes) {
        for (int index = exclusiveStart + 1; index < exclusiveEnd; index++) {
            if (!allowedTypes.contains(events.get(index).path("type").asText(""))) {
                return false;
            }
        }
        return true;
    }

    private static boolean answerLineageMatches(
            JsonNode events,
            int sourceIndex,
            int terminalIndex,
            String sourceHash,
            int sourceChars,
            String terminalHash,
            int terminalChars) {
        String currentHash = sourceHash;
        int currentChars = sourceChars;
        for (int index = sourceIndex + 1; index < terminalIndex; index++) {
            JsonNode event = events.get(index);
            if (!"ANSWER_TRANSFORMED".equals(event.path("type").asText(""))) continue;
            AnswerTransformation transformation = answerTransformation(event, index);
            if (transformation == null
                    || !transformation.fromAnswerHash().equals(currentHash)
                    || transformation.fromAnswerChars() != currentChars
                    || (transformation.answerHash().equals(currentHash)
                            && transformation.answerChars() == currentChars)) {
                return false;
            }
            currentHash = transformation.answerHash();
            currentChars = transformation.answerChars();
        }
        return Objects.equals(currentHash, terminalHash) && currentChars == terminalChars;
    }

    private static AnswerTransformation answerTransformation(JsonNode event, int eventIndex) {
        JsonNode data = event == null ? null : event.path("data");
        if (!exactKeys(data, Set.of(
                "fromAnswerHash", "fromAnswerChars",
                "answerHash", "answerChars", "reasonCode"))) {
            return null;
        }
        String fromHash = exactHash(data.path("fromAnswerHash"));
        int fromChars = exactNonNegativeInt(data.path("fromAnswerChars"));
        String answerHash = exactHash(data.path("answerHash"));
        int answerChars = exactNonNegativeInt(data.path("answerChars"));
        String reasonCode = data.path("reasonCode").isTextual()
                ? data.path("reasonCode").textValue()
                : "";
        if (fromHash == null || fromChars < 0 || answerHash == null || answerChars < 0
                || !ANSWER_TRANSFORMATION_REASON_CODES.contains(reasonCode)) {
            return null;
        }
        return new AnswerTransformation(
                eventIndex, fromHash, fromChars, answerHash, answerChars, reasonCode);
    }

    private static AnswerRestoration answerRestoration(JsonNode event, int eventIndex) {
        JsonNode data = event == null ? null : event.path("data");
        if (!exactKeys(data, Set.of(
                "answerHash", "answerChars", "origin",
                "sourceGenerationId", "sourceAttemptId", "reasonCode"))) {
            return null;
        }
        String answerHash = exactHash(data.path("answerHash"));
        int answerChars = exactNonNegativeInt(data.path("answerChars"));
        if (answerHash == null || answerChars < 0
                || !data.path("origin").isTextual()
                || !data.path("sourceGenerationId").isTextual()
                || !data.path("sourceAttemptId").isTextual()
                || !data.path("reasonCode").isTextual()
                || !ANSWER_RESTORATION_REASON_CODES.contains(
                        data.path("reasonCode").textValue())) {
            return null;
        }
        QualificationRecords.AnswerOrigin origin;
        try {
            origin = QualificationRecords.AnswerOrigin.valueOf(
                    data.path("origin").textValue());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (origin == QualificationRecords.AnswerOrigin.NONE) return null;
        String generationId = data.path("sourceGenerationId").textValue();
        String attemptId = data.path("sourceAttemptId").textValue();
        if (origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER) {
            generationId = exactId(data.path("sourceGenerationId"));
            attemptId = exactId(data.path("sourceAttemptId"));
            if (generationId == null || attemptId == null) return null;
        } else if (!generationId.isEmpty() || !attemptId.isEmpty()) {
            return null;
        }
        return new AnswerRestoration(
                eventIndex, origin, answerHash, answerChars,
                generationId, attemptId, data.path("reasonCode").textValue());
    }

    private static List<AnswerFingerprint> answerFingerprints(byte[] bytes) {
        String answer = strictUtf8(bytes);
        List<AnswerFingerprint> values = new ArrayList<>();
        values.add(new AnswerFingerprint(sha256(answer), answer.length()));
        String withoutCaptureEnding = removeOneTerminalLineEnding(answer);
        AnswerFingerprint stripped = new AnswerFingerprint(
                sha256(withoutCaptureEnding), withoutCaptureEnding.length());
        if (!values.contains(stripped)) values.add(stripped);
        return List.copyOf(values);
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("answer.txt is not valid UTF-8", invalid);
        }
    }

    private static boolean matches(
            List<AnswerFingerprint> expected,
            String hash,
            int chars) {
        return expected.stream().anyMatch(value -> value.hash().equals(hash)
                && (chars < 0 || value.chars() == chars));
    }

    private static boolean exactKeys(JsonNode object, Set<String> expected) {
        if (object == null || !object.isObject()) return false;
        Set<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static String exactHash(JsonNode value) {
        if (value == null || !value.isTextual()
                || !value.textValue().matches("sha256:[0-9a-f]{64}")) return null;
        return value.textValue();
    }

    private static String exactId(JsonNode value) {
        if (value == null || !value.isTextual()
                || !value.textValue().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")) return null;
        return value.textValue();
    }

    private static int exactInt(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue() : Integer.MIN_VALUE;
    }

    private static int exactNonNegativeInt(JsonNode value) {
        int parsed = exactInt(value);
        return parsed >= 0 ? parsed : -1;
    }

    private static int optionalExactInt(JsonNode value) {
        return value == null || value.isMissingNode() ? -1 : exactNonNegativeInt(value);
    }

    private record AnswerFingerprint(String hash, int chars) {}

    private record Selection(
            int eventIndex,
            QualificationRecords.AnswerOrigin origin,
            String answerHash,
            int answerChars) {}

    private record AnswerTransformation(
            int eventIndex,
            String fromAnswerHash,
            int fromAnswerChars,
            String answerHash,
            int answerChars,
            String reasonCode) {}

    private record AnswerRestoration(
            int eventIndex,
            QualificationRecords.AnswerOrigin origin,
            String answerHash,
            int answerChars,
            String sourceGenerationId,
            String sourceAttemptId,
            String reasonCode) {}

    private record SelectionChain(
            int terminalIndex,
            QualificationRecords.AnswerOrigin origin,
            String answerHash,
            int answerChars,
            String sourceGenerationId,
            String sourceAttemptId) {}

    private record ProviderToolSuppression(boolean present, boolean valid) {
        private static ProviderToolSuppression absent() {
            return new ProviderToolSuppression(false, true);
        }

        private static ProviderToolSuppression invalid() {
            return new ProviderToolSuppression(false, false);
        }
    }

    private static String removeOneTerminalLineEnding(String value) {
        if (value.endsWith("\r\n")) return value.substring(0, value.length() - 2);
        if (value.endsWith("\n")) return value.substring(0, value.length() - 1);
        return value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static List<String> immutableDistinct(List<String> values) {
        return values == null ? List.of() : List.copyOf(new LinkedHashSet<>(values));
    }
}
