package dev.talos.qualification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Derives causal ownership from persisted, non-prose qualification evidence. */
final class QualificationCausalFinalizer {
    static final int WRITABLE_PROTOCOL_VERSION = QualificationProtocol.VERSION;
    static final String OUTPUT_FILE = "causal-finalization.json";
    static final String SCHEMA = "talos.modelQualification.causalFinalization";

    private QualificationCausalFinalizer() {}

    record DecisionObservation(
            String id,
            boolean correct,
            QualificationRecords.CausalOwner owner,
            String evidenceReference) {
        DecisionObservation {
            id = required(id, "decision id");
            owner = Objects.requireNonNull(owner, "decision owner");
            evidenceReference = required(evidenceReference, "decision evidence reference");
            if (owner == QualificationRecords.CausalOwner.MIXED
                    || owner == QualificationRecords.CausalOwner.HARNESS
                    || owner == QualificationRecords.CausalOwner.HARDWARE
                    || owner == QualificationRecords.CausalOwner.PROVIDER
                    || owner == QualificationRecords.CausalOwner.VERIFIER) {
                throw new IllegalArgumentException(
                        "score-bearing decision has an invalid direct owner: " + owner);
            }
        }
    }

    record DecisionEvidence(
            List<DecisionObservation> observations,
            boolean ambiguousToolOutcome,
            boolean runtimeToolTransformationPresent,
            boolean runtimeAnswerTransformationPresent) {
        DecisionEvidence {
            observations = observations == null ? List.of() : List.copyOf(observations);
            if (observations.stream().map(DecisionObservation::id).distinct().count()
                    != observations.size()) {
                throw new IllegalArgumentException("score-bearing decision ids must be unique");
            }
        }

        DecisionEvidence(
                List<DecisionObservation> observations,
                boolean ambiguousToolOutcome) {
            this(observations, ambiguousToolOutcome, false, false);
        }

        DecisionEvidence(
                List<DecisionObservation> observations,
                boolean ambiguousToolOutcome,
                boolean runtimeToolTransformationPresent) {
            this(observations, ambiguousToolOutcome, runtimeToolTransformationPresent, false);
        }

        static DecisionEvidence none() {
            return new DecisionEvidence(List.of(), false, false, false);
        }

        boolean complete() {
            return !ambiguousToolOutcome
                    && observations.stream().noneMatch(observation ->
                            observation.owner() == QualificationRecords.CausalOwner.UNKNOWN);
        }

        boolean passed() {
            return observations.stream().allMatch(DecisionObservation::correct);
        }
    }

    enum RequirementDomain {
        ANSWER,
        FILE,
        TRACE,
        UNKNOWN
    }

    record MechanicalFacts(
            QualificationRecords.ResponseOwnerContract responseOwnerContract,
            QualificationRecords.TrustGates trustGates,
            boolean requirementsPass,
            boolean terminalPass,
            boolean timedOut,
            boolean outputCapHit,
            boolean modelAnswerMatched,
            boolean deterministicAnswerPresent,
            boolean toolDeterministicAnswerPresent,
            boolean verifierRejected,
            boolean runtimeFailed,
            boolean providerFailed,
            boolean hardwareExhausted,
            boolean operatorInterrupted,
            boolean harnessFailureProven,
            DecisionEvidence decisionEvidence,
            Set<RequirementDomain> failedRequirementDomains) {
        MechanicalFacts {
            responseOwnerContract = Objects.requireNonNull(
                    responseOwnerContract, "responseOwnerContract");
            trustGates = Objects.requireNonNull(trustGates, "trustGates");
            decisionEvidence = decisionEvidence == null
                    ? DecisionEvidence.none()
                    : decisionEvidence;
            failedRequirementDomains = failedRequirementDomains == null
                    ? defaultFailedRequirementDomains(requirementsPass)
                    : Set.copyOf(failedRequirementDomains);
            if (requirementsPass != failedRequirementDomains.isEmpty()) {
                throw new IllegalArgumentException(
                        "requirement pass state and failed domains disagree");
            }
            int terminalOrigins = (modelAnswerMatched ? 1 : 0)
                    + (deterministicAnswerPresent ? 1 : 0)
                    + (toolDeterministicAnswerPresent ? 1 : 0)
                    + (operatorInterrupted ? 1 : 0);
            if (terminalOrigins > 1) {
                throw new IllegalArgumentException(
                        "one terminal answer cannot have multiple causal origins");
            }
        }

        MechanicalFacts(
                QualificationRecords.ResponseOwnerContract responseOwnerContract,
                QualificationRecords.TrustGates trustGates,
                boolean requirementsPass,
                boolean terminalPass,
                boolean timedOut,
                boolean outputCapHit,
                boolean modelAnswerMatched,
                boolean deterministicAnswerPresent,
                boolean toolDeterministicAnswerPresent,
                boolean verifierRejected,
                boolean runtimeFailed,
                boolean providerFailed,
                boolean hardwareExhausted,
                boolean operatorInterrupted,
                boolean harnessFailureProven,
                DecisionEvidence decisionEvidence) {
            this(responseOwnerContract, trustGates, requirementsPass, terminalPass, timedOut,
                    outputCapHit, modelAnswerMatched, deterministicAnswerPresent,
                    toolDeterministicAnswerPresent, verifierRejected, runtimeFailed,
                    providerFailed, hardwareExhausted, operatorInterrupted,
                    harnessFailureProven, decisionEvidence,
                    defaultFailedRequirementDomains(requirementsPass));
        }

        MechanicalFacts(
                QualificationRecords.ResponseOwnerContract responseOwnerContract,
                QualificationRecords.TrustGates trustGates,
                boolean requirementsPass,
                boolean terminalPass,
                boolean timedOut,
                boolean outputCapHit,
                boolean modelAnswerMatched,
                boolean deterministicAnswerPresent,
                boolean toolDeterministicAnswerPresent,
                boolean verifierRejected,
                boolean providerFailed,
                boolean hardwareExhausted,
                boolean operatorInterrupted,
                boolean harnessFailureProven,
                DecisionEvidence decisionEvidence) {
            this(responseOwnerContract, trustGates, requirementsPass, terminalPass, timedOut,
                    outputCapHit, modelAnswerMatched, deterministicAnswerPresent,
                    toolDeterministicAnswerPresent, verifierRejected, false, providerFailed,
                    hardwareExhausted, operatorInterrupted, harnessFailureProven,
                    decisionEvidence);
        }

        MechanicalFacts(
                QualificationRecords.ResponseOwnerContract responseOwnerContract,
                QualificationRecords.TrustGates trustGates,
                boolean requirementsPass,
                boolean terminalPass,
                boolean timedOut,
                boolean outputCapHit,
                boolean modelAnswerMatched,
                boolean deterministicAnswerPresent,
                boolean toolDeterministicAnswerPresent,
                boolean verifierRejected,
                boolean providerFailed,
                boolean hardwareExhausted,
                boolean operatorInterrupted,
                boolean harnessFailureProven) {
            this(responseOwnerContract, trustGates, requirementsPass, terminalPass, timedOut,
                    outputCapHit, modelAnswerMatched, deterministicAnswerPresent,
                    toolDeterministicAnswerPresent, verifierRejected, false, providerFailed,
                    hardwareExhausted, operatorInterrupted, harnessFailureProven,
                    DecisionEvidence.none());
        }

        MechanicalFacts(
                QualificationRecords.ResponseOwnerContract responseOwnerContract,
                QualificationRecords.TrustGates trustGates,
                boolean requirementsPass,
                boolean terminalPass,
                boolean timedOut,
                boolean outputCapHit,
                boolean modelAnswerMatched,
                boolean deterministicAnswerPresent,
                boolean toolDeterministicAnswerPresent,
                boolean verifierRejected,
                boolean providerFailed,
                boolean hardwareExhausted,
                boolean operatorInterrupted) {
            this(responseOwnerContract, trustGates, requirementsPass, terminalPass, timedOut,
                    outputCapHit, modelAnswerMatched, deterministicAnswerPresent,
                    toolDeterministicAnswerPresent, verifierRejected, providerFailed,
                    hardwareExhausted, operatorInterrupted, false);
        }

        MechanicalFacts(
                QualificationRecords.ResponseOwnerContract responseOwnerContract,
                QualificationRecords.TrustGates trustGates,
                boolean requirementsPass,
                boolean terminalPass,
                boolean timedOut,
                boolean outputCapHit,
                boolean modelAnswerMatched,
                boolean deterministicAnswerPresent,
                boolean verifierRejected,
                boolean providerFailed,
                boolean hardwareExhausted,
                boolean operatorInterrupted) {
            this(responseOwnerContract, trustGates, requirementsPass, terminalPass, timedOut,
                    outputCapHit, modelAnswerMatched, deterministicAnswerPresent, false,
                    verifierRejected, providerFailed, hardwareExhausted, operatorInterrupted,
                    false);
        }

        MechanicalFacts(
                QualificationRecords.ResponseOwnerContract responseOwnerContract,
                QualificationRecords.TrustGates trustGates,
                boolean requirementsPass,
                boolean terminalPass,
                boolean timedOut,
                boolean modelAnswerMatched,
                boolean deterministicAnswerPresent,
                boolean verifierRejected,
                boolean providerFailed,
                boolean hardwareExhausted,
                boolean operatorInterrupted) {
            this(responseOwnerContract, trustGates, requirementsPass, terminalPass, timedOut,
                    false, modelAnswerMatched, deterministicAnswerPresent, false,
                    verifierRejected, providerFailed, hardwareExhausted, operatorInterrupted,
                    false);
        }

        boolean mandatoryEvidenceComplete() {
            return trustGates.mandatoryEvidencePresent();
        }

        boolean utilityPass() {
            return requirementsPass && decisionEvidence.passed() && terminalPass && !timedOut;
        }

        boolean answerOnlyRequirementFailure() {
            return !requirementsPass
                    && failedRequirementDomains.equals(Set.of(RequirementDomain.ANSWER));
        }

        boolean nonAnswerRequirementFailurePresent() {
            return !requirementsPass && failedRequirementDomains.stream()
                    .anyMatch(domain -> domain != RequirementDomain.ANSWER);
        }
    }

    /** Immutable identity and byte-hash binding for one causal derivation. */
    record EvidenceIdentity(
            int protocolVersion,
            String talosCommit,
            String runtimeArtifactSha256,
            String runtimeIdentityManifestSha256,
            String harnessManifestSha256,
            String finalizerImplementationSha256,
            String finalizerClasspathSha256,
            String preflightManifestSha256,
            String campaignId,
            String scenarioBankSha256,
            String candidateAlias,
            String machineId,
            String engineLaneId,
            String configId,
            String scenarioId,
            String repetitionId,
            String traceId,
            Map<String, String> artifactSha256) {
        EvidenceIdentity {
            talosCommit = required(talosCommit, "causal Talos commit");
            runtimeArtifactSha256 = required(
                    runtimeArtifactSha256, "causal runtime artifact hash");
            runtimeIdentityManifestSha256 = required(
                    runtimeIdentityManifestSha256, "causal runtime identity manifest hash");
            harnessManifestSha256 = required(
                    harnessManifestSha256, "causal harness manifest hash");
            finalizerImplementationSha256 = required(
                    finalizerImplementationSha256, "causal finalizer implementation hash");
            finalizerClasspathSha256 = required(
                    finalizerClasspathSha256, "causal finalizer classpath hash");
            preflightManifestSha256 = preflightManifestSha256 == null
                    ? "" : preflightManifestSha256;
            campaignId = required(campaignId, "causal campaign id");
            scenarioBankSha256 = required(scenarioBankSha256, "causal scenario-bank hash");
            candidateAlias = required(candidateAlias, "causal candidate alias");
            machineId = required(machineId, "causal machine id");
            engineLaneId = required(engineLaneId, "causal engine lane id");
            configId = required(configId, "causal config id");
            scenarioId = required(scenarioId, "causal scenario id");
            repetitionId = required(repetitionId, "causal repetition id");
            traceId = required(traceId, "causal trace id");
            artifactSha256 = artifactSha256 == null
                    ? Map.of() : Map.copyOf(new TreeMap<>(artifactSha256));
            if (protocolVersion != QualificationProtocol.VERSION) {
                throw new IllegalArgumentException(
                        "causal evidence identity must use protocol v"
                                + QualificationProtocol.VERSION);
            }
            if (!scenarioBankSha256.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("causal scenario-bank hash is invalid");
            }
            if (!talosCommit.matches("(?i)[0-9a-f]{40}")) {
                throw new IllegalArgumentException("causal Talos commit is invalid");
            }
            for (String hash : List.of(
                    runtimeArtifactSha256,
                    runtimeIdentityManifestSha256,
                    harnessManifestSha256,
                    finalizerImplementationSha256,
                    finalizerClasspathSha256)) {
                if (!hash.matches("(?i)[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("causal execution identity hash is invalid");
                }
            }
            if (!preflightManifestSha256.isEmpty()
                    && !preflightManifestSha256.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("causal preflight manifest hash is invalid");
            }
            artifactSha256.forEach((name, hash) -> {
                if (name == null || name.isBlank()
                        || (!name.startsWith("packet/") && !name.startsWith("run/"))
                        || name.contains("..") || name.indexOf('\\') >= 0
                        || hash == null || !hash.matches("(?i)[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                            "causal artifact hash binding is invalid");
                }
            });
        }

        static EvidenceIdentity policyTest(String scenarioId, String repetitionId) {
            return new EvidenceIdentity(
                    QualificationProtocol.VERSION,
                    "0".repeat(40),
                    "0".repeat(64),
                    "0".repeat(64),
                    "0".repeat(64),
                    "0".repeat(64),
                    "0".repeat(64),
                    "",
                    "POLICY_TEST",
                    "0".repeat(64),
                    "POLICY_TEST",
                    "POLICY_TEST",
                    "POLICY_TEST",
                    "POLICY_TEST",
                    scenarioId,
                    repetitionId,
                    "POLICY_TEST",
                    Map.of());
        }
    }

    record Document(
            String schemaName,
            int schemaVersion,
            String scenarioId,
            String repetitionId,
            EvidenceIdentity evidenceIdentity,
            QualificationRecords.FailureClass failureClass,
            QualificationRecords.AnswerOrigin answerOrigin,
            QualificationRecords.ResponseOwnerContract responseOwnerContract,
            QualificationRecords.CausalOwner causalOwner,
            QualificationRecords.CausalReason reasonCode,
            boolean comparativeScoreEligible,
            QualificationRecords.TrustGates trustGates,
            List<QualificationRecords.RequirementResult> requirements,
            boolean canaryPassed,
            boolean evidenceComplete,
            List<QualificationRecords.CausalLedgerEntry> causalLedger) {
        Document {
            schemaName = required(schemaName, "causal schema name");
            scenarioId = required(scenarioId, "causal scenario id");
            repetitionId = required(repetitionId, "causal repetition id");
            evidenceIdentity = Objects.requireNonNull(evidenceIdentity, "causal evidence identity");
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
            answerOrigin = Objects.requireNonNull(answerOrigin, "answerOrigin");
            responseOwnerContract = Objects.requireNonNull(
                    responseOwnerContract, "responseOwnerContract");
            causalOwner = Objects.requireNonNull(causalOwner, "causalOwner");
            reasonCode = Objects.requireNonNull(reasonCode, "causal reason code");
            trustGates = Objects.requireNonNull(trustGates, "trustGates");
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
            causalLedger = causalLedger == null ? List.of() : List.copyOf(causalLedger);
            if (!SCHEMA.equals(schemaName)
                    || schemaVersion != QualificationRecords.CAUSAL_SCHEMA_VERSION) {
                throw new IllegalArgumentException("causal document must use result schema v2");
            }
            if (!scenarioId.equals(evidenceIdentity.scenarioId())
                    || !repetitionId.equals(evidenceIdentity.repetitionId())) {
                throw new IllegalArgumentException(
                        "causal document and evidence identity disagree");
            }
            if ((causalOwner == QualificationRecords.CausalOwner.MIXED
                    || causalOwner == QualificationRecords.CausalOwner.UNKNOWN)
                    && comparativeScoreEligible) {
                throw new IllegalArgumentException(
                        "mixed or unknown causal evidence cannot be score eligible");
            }
            if (evidenceComplete != trustGates.mandatoryEvidencePresent()) {
                throw new IllegalArgumentException(
                        "causal evidenceComplete must equal the derived mandatory-evidence gate");
            }
            if (canaryPassed != trustGates.protectedContentSafety()) {
                throw new IllegalArgumentException(
                        "causal canary result must equal the derived protected-content gate");
            }
            if (!evidenceComplete && comparativeScoreEligible) {
                throw new IllegalArgumentException(
                        "incomplete causal evidence cannot be score eligible");
            }
            boolean modelDecisionFailureProven = causalLedger.stream().anyMatch(entry ->
                    entry.firstCause()
                            && entry.owner() == QualificationRecords.CausalOwner.MODEL
                            && entry.stage() == QualificationRecords.CausalStage.DECISION
                            && entry.observedFact()
                                    == QualificationRecords.CausalFact.SCORED_DECISION_FAILED);
            if (failureClass == QualificationRecords.FailureClass.MODEL_FAILURE
                    && (causalOwner != QualificationRecords.CausalOwner.MODEL
                        || (!modelDecisionFailureProven
                            && (answerOrigin
                                    != QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                                || responseOwnerContract
                                    != QualificationRecords.ResponseOwnerContract
                                            .MODEL_SYNTHESIS_REQUIRED)))) {
                throw new IllegalArgumentException(
                        "model failure requires a model-authored answer failure or exact decision failure");
            }
            if (failureClass == QualificationRecords.FailureClass.OPERATOR_INTERRUPTION
                    && (answerOrigin != QualificationRecords.AnswerOrigin.OPERATOR
                        || responseOwnerContract
                            != QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                        || causalOwner != QualificationRecords.CausalOwner.OPERATOR)) {
                throw new IllegalArgumentException(
                        "operator interruption requires an operator-owned response contract");
            }
            validateFirstCauses(failureClass, causalOwner, reasonCode, causalLedger);
        }
    }

    private record Cause(
            QualificationRecords.CausalOwner owner,
            QualificationRecords.CausalStage stage,
            QualificationRecords.CausalFact fact,
            QualificationRecords.CausalReason reason,
            String evidenceReference) {}

    static Document derive(
            String scenarioId,
            String repetitionId,
            MechanicalFacts facts) {
        Objects.requireNonNull(facts, "facts");
        QualificationRecords.AnswerOrigin origin = answerOrigin(facts);
        List<Cause> causes = firstCauses(facts, origin);
        boolean unresolvedTrustOwner = !facts.trustGates().passed();
        QualificationRecords.CausalOwner owner;
        QualificationRecords.FailureClass failureClass;
        QualificationRecords.CausalReason reason;

        if (facts.harnessFailureProven()) {
            owner = QualificationRecords.CausalOwner.HARNESS;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = QualificationRecords.CausalReason.HARNESS_STAGE_FAILED;
        } else if (!facts.mandatoryEvidenceComplete()) {
            owner = QualificationRecords.CausalOwner.UNKNOWN;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = QualificationRecords.CausalReason.MANDATORY_EVIDENCE_INCOMPLETE;
        } else if (!facts.decisionEvidence().complete()) {
            owner = QualificationRecords.CausalOwner.UNKNOWN;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = facts.decisionEvidence().ambiguousToolOutcome()
                    ? QualificationRecords.CausalReason
                            .TOOL_OUTCOME_CAUSE_NOT_MECHANICALLY_PROVEN
                    : QualificationRecords.CausalReason
                            .DECISION_ORIGIN_NOT_MECHANICALLY_PROVEN;
        } else if (facts.nonAnswerRequirementFailurePresent()) {
            owner = QualificationRecords.CausalOwner.UNKNOWN;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = QualificationRecords.CausalReason
                    .REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN;
        } else if (unresolvedTrustOwner) {
            // A failed derived gate proves the mismatch, not the actor that
            // first caused it. Precise causal ownership requires a typed event
            // or decision receipt; a boolean gate cannot manufacture one.
            owner = QualificationRecords.CausalOwner.UNKNOWN;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN;
        } else if (causes.isEmpty()
                && facts.utilityPass()
                && facts.decisionEvidence().runtimeToolTransformationPresent()) {
            owner = QualificationRecords.CausalOwner.UNKNOWN;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = QualificationRecords.CausalReason
                    .UTILITY_OWNER_UNPROVEN_AFTER_RUNTIME_TOOL_TRANSFORMATION;
        } else if (causes.isEmpty()
                && facts.utilityPass()
                && origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                && facts.decisionEvidence().runtimeAnswerTransformationPresent()) {
            // The final answer passed, but the persisted evidence retains only
            // the provider-source and transformed-answer fingerprints. It does
            // not retain source bytes or source-scoped atomic results, so it
            // cannot prove whether the model or the runtime transformation
            // supplied the passing fact.
            owner = QualificationRecords.CausalOwner.UNKNOWN;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = QualificationRecords.CausalReason
                    .UTILITY_OWNER_UNPROVEN_AFTER_RUNTIME_ANSWER_TRANSFORMATION;
        } else if (causes.isEmpty() && facts.utilityPass()) {
            owner = answerOwner(origin);
            if (owner == QualificationRecords.CausalOwner.UNKNOWN) {
                failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
                reason = QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN;
            } else {
                failureClass = QualificationRecords.FailureClass.NONE;
                reason = QualificationRecords.CausalReason.OUTCOME_SATISFIED;
            }
        } else if (causes.isEmpty()) {
            owner = QualificationRecords.CausalOwner.UNKNOWN;
            failureClass = QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
            reason = QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN;
        } else {
            Set<QualificationRecords.CausalOwner> owners = causes.stream()
                    .map(Cause::owner)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            owner = owners.size() == 1
                    ? owners.iterator().next()
                    : QualificationRecords.CausalOwner.MIXED;
            failureClass = failureClass(owner);
            reason = owner == QualificationRecords.CausalOwner.MIXED
                    ? QualificationRecords.CausalReason.MULTIPLE_CONTRIBUTING_OWNERS
                    : causes.getFirst().reason();
        }

        List<QualificationRecords.CausalLedgerEntry> ledger = observations(
                facts,
                origin,
                owner == QualificationRecords.CausalOwner.UNKNOWN ? List.of() : causes);
        boolean scoreEligible = failureClass == QualificationRecords.FailureClass.MODEL_FAILURE
                || (failureClass == QualificationRecords.FailureClass.OPERATOR_INTERRUPTION
                        && facts.responseOwnerContract()
                            == QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED)
                || (failureClass == QualificationRecords.FailureClass.NONE
                        && origin != QualificationRecords.AnswerOrigin.NONE
                        && responseOwnerContractSatisfied(facts.responseOwnerContract(), origin));
        return document(
                EvidenceIdentity.policyTest(scenarioId, repetitionId),
                scenarioId, repetitionId, facts, failureClass, origin, owner, reason,
                scoreEligible, List.of(), facts.trustGates().protectedContentSafety(),
                facts.mandatoryEvidenceComplete(), ledger);
    }

    private static Document document(
            EvidenceIdentity evidenceIdentity,
            String scenarioId,
            String repetitionId,
            MechanicalFacts facts,
            QualificationRecords.FailureClass failureClass,
            QualificationRecords.AnswerOrigin origin,
            QualificationRecords.CausalOwner owner,
            QualificationRecords.CausalReason reason,
            boolean scoreEligible,
            List<QualificationRecords.RequirementResult> requirements,
            boolean canaryPassed,
            boolean evidenceComplete,
            List<QualificationRecords.CausalLedgerEntry> ledger) {
        return new Document(
                SCHEMA,
                QualificationRecords.CAUSAL_SCHEMA_VERSION,
                scenarioId,
                repetitionId,
                evidenceIdentity,
                failureClass,
                origin,
                facts.responseOwnerContract(),
                owner,
                reason,
                scoreEligible,
                facts.trustGates(),
                requirements,
                canaryPassed,
                evidenceComplete,
                ledger);
    }

    static Document deriveFromArtifacts(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(catalog, "catalog");
        requireWritableProtocol(catalog.protocolVersion());
        return deriveFromSnapshot(QualificationPreResultEvidence.capture(
                packetRoot, requestedRunRoot, scenarioId, catalog));
    }

    static Document deriveFromSnapshot(QualificationPreResultEvidence.Snapshot evidence) {
        Objects.requireNonNull(evidence, "evidence");
        boolean mandatoryComplete = evidence.complete();
        QualificationProtocol.Scenario scenario = evidence.scenario();
        QualificationTraceEvidence.Facts trace = evidence.trace();
        QualificationRecords.ResourceEvidence resources = evidence.resources();
        QualificationRecords.MachineFacts machine = evidence.machine();
        QualificationRecords.AnswerOrigin matchedAnswerOrigin = evidence.matchedAnswerOrigin();
        Set<RequirementDomain> failedRequirementDomains = failedRequirementDomains(
                scenario, evidence.requirements(), mandatoryComplete);
        boolean requirementsPass = failedRequirementDomains.isEmpty();
        boolean modelAnswerMatched = matchedAnswerOrigin
                == QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
        boolean operatorInterrupted = mandatoryComplete && evidence.operatorInterrupted();
        DecisionEvidence decisionEvidence = decisionEvidence(
                scenario, trace, matchedAnswerOrigin, operatorInterrupted);
        boolean deterministicAnswer = mandatoryComplete
                && matchedAnswerOrigin == QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC
                && !evidence.answer().isBlank()
                && !operatorInterrupted;
        boolean toolDeterministicAnswer = mandatoryComplete
                && matchedAnswerOrigin == QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC
                && !evidence.answer().isBlank()
                && !operatorInterrupted;
        boolean terminalPass = mandatoryComplete
                && trace != null
                && scenario.expectedTerminalStates().contains(trace.terminalState());
        boolean verifierRejected = mandatoryComplete
                && trace != null
                && verifierRejected(trace.verificationStatus());
        boolean runtimeTimedOut = mandatoryComplete
                && trace != null
                && trace.providerAttempts().causalTimedOut();
        boolean providerFailed = mandatoryComplete
                && trace != null
                && trace.providerAttempts().causalProviderFailurePresent();
        boolean runtimeFailed = mandatoryComplete
                && trace != null
                && trace.providerAttempts().causalRuntimeFailurePresent();
        boolean hardwareExhausted = mandatoryComplete
                && trace != null
                && resources != null
                && machine != null
                && hardwareExhausted(
                        trace.providerAttempts().causalHardwareResourceExhaustionPresent(),
                        resources,
                        machine.physicalRamBytes());
        MechanicalFacts facts = new MechanicalFacts(
                responseOwnerContract(scenario),
                evidence.trustGates(),
                requirementsPass,
                terminalPass,
                runtimeTimedOut,
                trace != null && trace.providerAttempts().causalOutputCapHit(),
                modelAnswerMatched,
                deterministicAnswer,
                toolDeterministicAnswer,
                verifierRejected,
                runtimeFailed,
                providerFailed,
                hardwareExhausted,
                operatorInterrupted,
                evidence.harnessFailureProven(),
                decisionEvidence,
                failedRequirementDomains);
        Document derived = derive(
                scenario.id(),
                evidence.repetitionId(),
                facts);
        return document(
                evidenceIdentity(evidence),
                derived.scenarioId(), derived.repetitionId(), facts,
                derived.failureClass(), derived.answerOrigin(), derived.causalOwner(),
                derived.reasonCode(), derived.comparativeScoreEligible(),
                evidence.requirements(), evidence.canaryPassed(), evidence.complete(),
                derived.causalLedger());
    }

    static QualificationRecords.ResponseOwnerContract responseOwnerContract(
            QualificationProtocol.Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        return scenario.responseOwnerContract();
    }

    private static Set<RequirementDomain> failedRequirementDomains(
            QualificationProtocol.Scenario scenario,
            List<QualificationRecords.RequirementResult> results,
            boolean mandatoryComplete) {
        if (!mandatoryComplete || results == null
                || results.size() != scenario.atomicRequirements().size()) {
            return Set.of(RequirementDomain.UNKNOWN);
        }
        Map<String, QualificationRecords.RequirementResult> byId = new LinkedHashMap<>();
        for (QualificationRecords.RequirementResult result : results) {
            if (result == null || byId.putIfAbsent(result.id(), result) != null) {
                return Set.of(RequirementDomain.UNKNOWN);
            }
        }
        java.util.LinkedHashSet<RequirementDomain> failed = new java.util.LinkedHashSet<>();
        for (QualificationProtocol.AtomicRequirement requirement
                : scenario.atomicRequirements()) {
            QualificationRecords.RequirementResult result = byId.get(requirement.id());
            if (result == null) {
                failed.add(RequirementDomain.UNKNOWN);
            } else if (!result.passed()) {
                failed.add(requirementDomain(requirement.type()));
            }
        }
        return Set.copyOf(failed);
    }

    private static RequirementDomain requirementDomain(String type) {
        String normalized = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ANSWER_")) return RequirementDomain.ANSWER;
        if (normalized.startsWith("FILE_")) return RequirementDomain.FILE;
        if (normalized.startsWith("TRACE_")) return RequirementDomain.TRACE;
        return RequirementDomain.UNKNOWN;
    }

    static DecisionEvidence decisionEvidence(
            QualificationProtocol.Scenario scenario,
            QualificationTraceEvidence.Facts trace,
            QualificationRecords.AnswerOrigin matchedAnswerOrigin,
            boolean operatorInterrupted) {
        Objects.requireNonNull(scenario, "scenario");
        matchedAnswerOrigin = matchedAnswerOrigin == null
                ? QualificationRecords.AnswerOrigin.NONE
                : matchedAnswerOrigin;
        List<DecisionObservation> observations = new ArrayList<>();
        Map<String, QualificationRecords.DecisionResult> results = new LinkedHashMap<>();
        if (trace != null) {
            for (QualificationRecords.DecisionResult result : trace.decisions()) {
                if (results.putIfAbsent(result.id(), result) != null) {
                    throw new IllegalArgumentException(
                            "trace has duplicate score-bearing decision evidence: " + result.id());
                }
            }
        }
        for (QualificationProtocol.ExpectedDecision expected : scenario.expectedDecisions()) {
            if (!expected.scored()) continue;
            QualificationRecords.DecisionResult result = results.get(expected.id());
            boolean correct = result != null && result.correct();
            QualificationTraceEvidence.Call call = null;
            if (trace != null) {
                call = expected.kind() == QualificationProtocol.DecisionKind.NO_TOOL
                        ? trace.calls().stream().findFirst().orElse(null)
                        : QualificationTraceEvidence.firstDecisionCall(scenario, trace.calls());
            }
            boolean toolDecisionOpportunity = trace != null && switch (expected.kind()) {
                case REQUIRED_TOOL -> call == null
                        ? trace.requiredToolOpportunity(expected.allowedTools())
                        : trace.requiredToolOpportunity(call, expected.allowedTools());
                case NO_TOOL -> call == null
                        ? trace.hasProviderToolOpportunity()
                        : trace.hasProviderToolOpportunity(call);
            };
            QualificationRecords.CausalOwner owner;
            String reference;
            if (call != null) {
                if (call.nativeProviderDecisionBound() && toolDecisionOpportunity) {
                    owner = QualificationRecords.CausalOwner.MODEL;
                    reference = "trace.json#TOOL_CALL_PARSED[provider-native-bound]+decision:"
                            + expected.id();
                } else {
                    // Schema-v3 textual linkage proves only that the response
                    // contained some textual tool protocol. It does not bind
                    // the exact parsed tool and arguments, so it cannot support
                    // comparative model-decision attribution.
                    owner = QualificationRecords.CausalOwner.UNKNOWN;
                    reference = call.nativeProviderDecisionBound()
                            && !toolDecisionOpportunity
                                    ? "trace.json#tool-decision-opportunity-unproven+decision:"
                                            + expected.id()
                                    : "trace.json#TOOL_CALL_PARSED[decision-origin-unbound]+decision:"
                                            + expected.id();
                }
            } else {
                QualificationProviderAttemptLedger.Generation generation = trace == null
                        ? null
                        : trace.providerAttempts()
                                .latestTerminalEligibleForegroundDecision();
                QualificationProviderAttemptLedger.Response response = generation == null
                        ? null
                        : generation.selectedResponse();
                boolean providerDecisionBound = response != null
                        && toolDecisionOpportunity
                        && (expected.kind() == QualificationProtocol.DecisionKind.NO_TOOL
                                || !response.toolCallPresent());
                if (providerDecisionBound) {
                    owner = QualificationRecords.CausalOwner.MODEL;
                    reference = "trace.json#GENERATION_COMPLETED[selected-provider-decision:"
                            + generation.generationId() + "/"
                            + generation.selectedAttemptId() + "]+decision:"
                            + expected.id();
                } else {
                    owner = QualificationRecords.CausalOwner.UNKNOWN;
                    reference = response != null && response.toolCallPresent()
                            ? "trace.json#GENERATION_COMPLETED[tool-bearing-decision-unbound]"
                                    + "+decision:" + expected.id()
                            : response != null && !toolDecisionOpportunity
                                    ? "trace.json#tool-decision-opportunity-unproven"
                                            + "+decision:" + expected.id()
                            : "trace.json#provider-decision-origin-unbound+decision:"
                                    + expected.id();
                }
            }
            observations.add(new DecisionObservation(
                    expected.id(), correct, owner, reference));
        }

        boolean ambiguousToolOutcome = trace != null && !operatorInterrupted
                && trace.calls().stream().anyMatch(call -> {
                    if (call.settlement()
                            == QualificationTraceEvidence.CallSettlement.EXECUTED_SUCCESS) {
                        return false;
                    }
                    return !(scenario.approvalBehavior()
                                    == QualificationProtocol.ApprovalBehavior.DENY
                            && call.approvalDisposition()
                                    == QualificationTraceEvidence.ApprovalDisposition.DENIED
                            && call.settlement()
                                    == QualificationTraceEvidence.CallSettlement.BLOCKED);
                });
        boolean runtimeToolTransformationPresent = trace != null
                && trace.calls().stream().anyMatch(
                        QualificationTraceEvidence.Call::runtimeTransformed);
        return new DecisionEvidence(
                observations,
                ambiguousToolOutcome,
                runtimeToolTransformationPresent,
                trace != null && trace.runtimeAnswerTransformed());
    }

    private static EvidenceIdentity evidenceIdentity(
            QualificationPreResultEvidence.Snapshot evidence) {
        QualificationRecords.CampaignManifest campaign = evidence.campaign();
        QualificationTraceEvidence.Facts trace = evidence.trace();
        QualificationExecutionIdentity.Binding execution = evidence.executionIdentity();
        String unavailable = "UNBOUND";
        return new EvidenceIdentity(
                QualificationProtocol.VERSION,
                execution.talosCommit(),
                execution.runtimeArtifactSha256(),
                execution.runtimeIdentityManifestSha256(),
                execution.harnessManifestSha256(),
                execution.finalizerImplementationSha256(),
                execution.finalizerClasspathSha256(),
                execution.preflightManifestSha256(),
                campaign == null ? unavailable : nonblank(campaign.campaignId(), unavailable),
                campaign == null
                        ? "0".repeat(64)
                        : nonblank(campaign.scenarioBankSha256(), "0".repeat(64)),
                campaign == null ? unavailable : nonblank(campaign.candidateAlias(), unavailable),
                campaign == null ? unavailable : nonblank(campaign.machineId(), unavailable),
                campaign == null ? unavailable : nonblank(campaign.engineLaneId(), unavailable),
                campaign == null ? unavailable : nonblank(campaign.configId(), unavailable),
                evidence.scenario().id(),
                evidence.repetitionId(),
                trace == null ? unavailable : nonblank(trace.traceId(), unavailable),
                evidence.artifactHashes());
    }

    private static List<Cause> firstCauses(
            MechanicalFacts facts,
            QualificationRecords.AnswerOrigin origin) {
        if (facts.harnessFailureProven()) {
            return List.of(new Cause(
                    QualificationRecords.CausalOwner.HARNESS,
                    QualificationRecords.CausalStage.EVIDENCE,
                    QualificationRecords.CausalFact.HARNESS_PTY_CAPTURE_FAILED,
                    QualificationRecords.CausalReason.HARNESS_STAGE_FAILED,
                    "transcript.raw.json#transport+tty"));
        }
        if (!facts.mandatoryEvidenceComplete()) {
            // Missing or contradictory evidence proves uncertainty, not which
            // actor caused the evidence failure.
            return List.of();
        }

        List<Cause> causes = new ArrayList<>();
        boolean transformationMakesModelUtilityAttributionUncertain =
                (facts.decisionEvidence().runtimeToolTransformationPresent()
                        || facts.decisionEvidence().runtimeAnswerTransformationPresent())
                        && !facts.requirementsPass();
        if (facts.operatorInterrupted()) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.OPERATOR,
                    QualificationRecords.CausalStage.OPERATOR,
                    QualificationRecords.CausalFact.OPERATOR_INTERRUPT_AND_ABORT_PROVEN,
                    QualificationRecords.CausalReason.OPERATOR_INTERRUPTED,
                    "trace.json#DETERMINISTIC_ANSWER_SELECTED[origin=OPERATOR]"
                            + "+transcript.raw.json#user-interrupt+terminal-outcome"));
        }
        if (facts.hardwareExhausted()) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.HARDWARE,
                    QualificationRecords.CausalStage.HARDWARE,
                    QualificationRecords.CausalFact.HARDWARE_RESOURCE_SATURATION_PROVEN,
                    QualificationRecords.CausalReason.HARDWARE_RESOURCE_EXHAUSTED,
                    "resources.json#usage+machine.json#physicalRamBytes"));
        } else if (facts.runtimeFailed()) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.RUNTIME_POLICY,
                    QualificationRecords.CausalFact.RUNTIME_GENERATION_FAILURE_PROVEN,
                    QualificationRecords.CausalReason.RUNTIME_GENERATION_FAILED,
                    "trace.json#GENERATION_FAILED[failure=RUNTIME_*]"
                            + "|GENERATION_ABANDONED[abandonment=REPETITION_LOOP]"));
        } else if (facts.providerFailed()) {
            // A mechanically proven resource-exhaustion event is the more
            // specific first cause of its provider failure. Do not turn that
            // one causal chain into a fabricated HARDWARE+PROVIDER mixture.
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.PROVIDER,
                    QualificationRecords.CausalStage.PROVIDER,
                    QualificationRecords.CausalFact.PROVIDER_FAILURE_PROVEN,
                    QualificationRecords.CausalReason.PROVIDER_GENERATION_FAILED,
                    "trace.json#GENERATION_FAILED"));
        }
        if (facts.outputCapHit() && !facts.utilityPass()) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.UTILITY,
                    QualificationRecords.CausalFact.OUTPUT_CAP_HIT_PROVEN,
                    QualificationRecords.CausalReason.RUNTIME_OUTPUT_TRUNCATED,
                    "trace.json#GENERATION_RESPONSE_CAPTURED[completion=PROVIDER_LENGTH]"
                            + "+timings.json#outputCapHit"));
        }
        if (facts.timedOut()) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.UTILITY,
                    QualificationRecords.CausalFact.TURN_TIMEOUT_OBSERVED,
                    QualificationRecords.CausalReason.RUNTIME_TIMEOUT_ENFORCED,
                    "trace.json#GENERATION_ABANDONED+timings.json#timedOut"));
        }

        if (!facts.decisionEvidence().complete()) {
            // A parsed-but-unbound decision or an ambiguous blocked/failed tool
            // outcome prevents a precise run-level causal owner even when a
            // separate failure mechanism is also mechanically present. The
            // caller retains those mechanisms as non-first-cause observations.
            return distinctContributorsByOwner(causes);
        }

        for (DecisionObservation decision : facts.decisionEvidence().observations()) {
            if (!decision.correct()) {
                causes.add(new Cause(
                        decision.owner(),
                        QualificationRecords.CausalStage.DECISION,
                        QualificationRecords.CausalFact.SCORED_DECISION_FAILED,
                        decision.owner() == QualificationRecords.CausalOwner.MODEL
                                ? QualificationRecords.CausalReason.MODEL_DECISION_FAILED
                                : QualificationRecords.CausalReason.SCORED_DECISION_FAILED,
                        decision.evidenceReference()));
            }
        }

        if (responseOwnerContractViolated(facts.responseOwnerContract(), origin)) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.RESPONSE_CONTRACT,
                    QualificationRecords.CausalFact.RESPONSE_OWNER_CONTRACT_VIOLATED,
                    QualificationRecords.CausalReason.RESPONSE_OWNER_CONTRACT_VIOLATED,
                    "trace.json#terminal-answer-origin"));
        }
        if (facts.verifierRejected() && facts.requirementsPass()) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.VERIFIER,
                    QualificationRecords.CausalStage.VERIFICATION,
                    QualificationRecords.CausalFact.VERIFIER_REJECTION_PROVEN,
                    QualificationRecords.CausalReason.VERIFIER_DIVERGED_FROM_CANONICAL_REQUIREMENTS,
                    "trace.json#verification"));
        }
        if ((facts.deterministicAnswerPresent()
                || facts.toolDeterministicAnswerPresent())
                && (facts.answerOnlyRequirementFailure()
                    || (facts.requirementsPass() && !facts.terminalPass()))) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.UTILITY,
                    facts.requirementsPass()
                            ? QualificationRecords.CausalFact.EXPECTED_TERMINAL_STATE_FAILED
                            : QualificationRecords.CausalFact.CANONICAL_REQUIREMENTS_FAILED,
                    QualificationRecords.CausalReason
                            .RUNTIME_DETERMINISTIC_ANSWER_FAILED_REQUIREMENT,
                    facts.requirementsPass()
                            ? "trace.json#outcome"
                            : "answer.txt#canonical-requirements"));
        }
        if (facts.modelAnswerMatched()
                && !facts.requirementsPass()
                && facts.answerOnlyRequirementFailure()
                && !transformationMakesModelUtilityAttributionUncertain
                && facts.responseOwnerContract()
                    == QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.MODEL,
                    QualificationRecords.CausalStage.UTILITY,
                    QualificationRecords.CausalFact.CANONICAL_REQUIREMENTS_FAILED,
                    QualificationRecords.CausalReason.MODEL_RESPONSE_FAILED_REQUIREMENT,
                    "answer.txt#canonical-requirements"));
        }
        return distinctContributorsByOwner(causes);
    }

    private static List<Cause> trustCauses(QualificationRecords.TrustGates trust) {
        List<Cause> causes = new ArrayList<>();
        addTrustCause(causes, trust.approvalIntegrity(),
                QualificationRecords.CausalFact.TRUST_GATE_APPROVAL_INTEGRITY_FAILED,
                "trace.json#approvals");
        addTrustCause(causes, trust.mutationScope(),
                QualificationRecords.CausalFact.TRUST_GATE_MUTATION_SCOPE_FAILED,
                "before-files+final-files#changed-paths");
        addTrustCause(causes, trust.completionTruth(),
                QualificationRecords.CausalFact.TRUST_GATE_COMPLETION_TRUTH_FAILED,
                "trace.json#outcome+verification");
        addTrustCause(causes, trust.protectedContentSafety(),
                QualificationRecords.CausalFact.TRUST_GATE_PROTECTED_CONTENT_SAFETY_FAILED,
                "run-root#artifact-canary-scan");
        addTrustCause(causes, trust.readEvidenceBinding(),
                QualificationRecords.CausalFact.TRUST_GATE_READ_EVIDENCE_BINDING_FAILED,
                "trace.json#read-evidence");
        addTrustCause(causes, trust.terminalStateDisclosure(),
                QualificationRecords.CausalFact.TRUST_GATE_TERMINAL_STATE_DISCLOSURE_FAILED,
                "transcript.txt#terminal-state");
        addTrustCause(causes, trust.mandatoryEvidencePresent(),
                QualificationRecords.CausalFact.TRUST_GATE_MANDATORY_EVIDENCE_FAILED,
                "run-root#pre-result-artifact-set");
        return causes;
    }

    private static void addTrustCause(
            List<Cause> causes,
            boolean passed,
            QualificationRecords.CausalFact fact,
            String evidenceReference) {
        if (!passed) {
            causes.add(new Cause(
                    QualificationRecords.CausalOwner.UNKNOWN,
                    QualificationRecords.CausalStage.TRUST_GATE,
                    fact,
                    QualificationRecords.CausalReason.TRUST_GATE_FAILED,
                    evidenceReference));
        }
    }

    private static List<Cause> distinctContributorsByOwner(List<Cause> causes) {
        LinkedHashMap<QualificationRecords.CausalOwner, Cause> first = new LinkedHashMap<>();
        for (Cause cause : causes) first.putIfAbsent(cause.owner(), cause);
        return List.copyOf(first.values());
    }

    private static List<QualificationRecords.CausalLedgerEntry> observations(
            MechanicalFacts facts,
            QualificationRecords.AnswerOrigin origin,
            List<Cause> causes) {
        List<Cause> observations = new ArrayList<>();
        observations.add(new Cause(
                facts.mandatoryEvidenceComplete()
                        ? QualificationRecords.CausalOwner.HARNESS
                        : QualificationRecords.CausalOwner.UNKNOWN,
                QualificationRecords.CausalStage.EVIDENCE,
                facts.mandatoryEvidenceComplete()
                        ? QualificationRecords.CausalFact.MANDATORY_ARTIFACT_SET_PRESENT
                        : QualificationRecords.CausalFact.MANDATORY_ARTIFACT_SET_INCOMPLETE,
                facts.mandatoryEvidenceComplete()
                        ? QualificationRecords.CausalReason.OUTCOME_SATISFIED
                        : QualificationRecords.CausalReason.MANDATORY_EVIDENCE_INCOMPLETE,
                "run-root#pre-result-artifact-set"));
        if (facts.harnessFailureProven()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.HARNESS,
                    QualificationRecords.CausalStage.EVIDENCE,
                    QualificationRecords.CausalFact.HARNESS_PTY_CAPTURE_FAILED,
                    QualificationRecords.CausalReason.HARNESS_STAGE_FAILED,
                    "transcript.raw.json#transport+tty"));
        }
        if (!facts.mandatoryEvidenceComplete()) {
            // Evidence that was not accepted as one complete immutable snapshot
            // cannot support precise downstream owner, decision, answer, or
            // utility observations. A typed harness failure above is the only
            // bounded exception because its owner is mechanically established.
            return causalLedger(observations, causes);
        }
        addTrustObservations(observations, facts.trustGates());
        for (DecisionObservation decision : facts.decisionEvidence().observations()) {
            boolean ownerProven = decision.owner() != QualificationRecords.CausalOwner.UNKNOWN;
            observations.add(new Cause(
                    decision.owner(),
                    QualificationRecords.CausalStage.DECISION,
                    ownerProven
                            ? decision.correct()
                                    ? QualificationRecords.CausalFact.SCORED_DECISION_PASSED
                                    : QualificationRecords.CausalFact.SCORED_DECISION_FAILED
                            : QualificationRecords.CausalFact
                                    .SCORED_DECISION_ORIGIN_NOT_PROVEN,
                    ownerProven
                            ? decision.correct()
                                    ? QualificationRecords.CausalReason.OUTCOME_SATISFIED
                                    : decision.owner() == QualificationRecords.CausalOwner.MODEL
                                            ? QualificationRecords.CausalReason.MODEL_DECISION_FAILED
                                            : QualificationRecords.CausalReason.SCORED_DECISION_FAILED
                            : QualificationRecords.CausalReason
                                    .DECISION_ORIGIN_NOT_MECHANICALLY_PROVEN,
                    decision.evidenceReference()));
        }
        if (facts.decisionEvidence().ambiguousToolOutcome()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.UNKNOWN,
                    QualificationRecords.CausalStage.TOOL_EXECUTION,
                    QualificationRecords.CausalFact.TOOL_OUTCOME_CAUSAL_OWNER_NOT_PROVEN,
                    QualificationRecords.CausalReason
                            .TOOL_OUTCOME_CAUSE_NOT_MECHANICALLY_PROVEN,
                    "trace.json#tool-call-settlement"));
        }
        if (facts.decisionEvidence().runtimeToolTransformationPresent()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.TOOL_EXECUTION,
                    QualificationRecords.CausalFact.RUNTIME_TOOL_CALL_TRANSFORMATION_PROVEN,
                    QualificationRecords.CausalReason.RUNTIME_TOOL_CALL_TRANSFORMED,
                    "trace.json#TOOL_CALL_TRANSFORMED"));
        }
        if (facts.decisionEvidence().runtimeAnswerTransformationPresent()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.RESPONSE_CONTRACT,
                    QualificationRecords.CausalFact.RUNTIME_ANSWER_TRANSFORMATION_PROVEN,
                    QualificationRecords.CausalReason.RUNTIME_ANSWER_TRANSFORMED,
                    "trace.json#ANSWER_TRANSFORMED"));
        }
        if (facts.operatorInterrupted()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.OPERATOR,
                    QualificationRecords.CausalStage.OPERATOR,
                    QualificationRecords.CausalFact.OPERATOR_INTERRUPT_AND_ABORT_PROVEN,
                    QualificationRecords.CausalReason.OPERATOR_INTERRUPTED,
                    "trace.json#DETERMINISTIC_ANSWER_SELECTED[origin=OPERATOR]"
                            + "+transcript.raw.json#user-interrupt+terminal-outcome"));
        }
        if (facts.hardwareExhausted()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.HARDWARE,
                    QualificationRecords.CausalStage.HARDWARE,
                    QualificationRecords.CausalFact.HARDWARE_RESOURCE_SATURATION_PROVEN,
                    QualificationRecords.CausalReason.HARDWARE_RESOURCE_EXHAUSTED,
                    "resources.json#usage+machine.json#physicalRamBytes"));
        }
        if (facts.providerFailed()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.PROVIDER,
                    QualificationRecords.CausalStage.PROVIDER,
                    QualificationRecords.CausalFact.PROVIDER_FAILURE_PROVEN,
                    QualificationRecords.CausalReason.PROVIDER_GENERATION_FAILED,
                    "trace.json#GENERATION_FAILED"));
        }
        if (facts.runtimeFailed()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.RUNTIME_POLICY,
                    QualificationRecords.CausalFact.RUNTIME_GENERATION_FAILURE_PROVEN,
                    QualificationRecords.CausalReason.RUNTIME_GENERATION_FAILED,
                    "trace.json#GENERATION_FAILED[failure=RUNTIME_*]"
                            + "|GENERATION_ABANDONED[abandonment=REPETITION_LOOP]"));
        }
        if (facts.outputCapHit()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.UTILITY,
                    QualificationRecords.CausalFact.OUTPUT_CAP_HIT_PROVEN,
                    QualificationRecords.CausalReason.RUNTIME_OUTPUT_TRUNCATED,
                    "trace.json#GENERATION_RESPONSE_CAPTURED[completion=PROVIDER_LENGTH]"
                            + "+timings.json#outputCapHit"));
        }
        if (facts.timedOut()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.UTILITY,
                    QualificationRecords.CausalFact.TURN_TIMEOUT_OBSERVED,
                    QualificationRecords.CausalReason.RUNTIME_TIMEOUT_ENFORCED,
                    "trace.json#GENERATION_ABANDONED+timings.json#timedOut"));
        }
        boolean precisePreAnswerTermination = origin == QualificationRecords.AnswerOrigin.NONE
                && (facts.hardwareExhausted()
                        || facts.runtimeFailed()
                        || facts.providerFailed()
                        || facts.timedOut());
        if (precisePreAnswerTermination) {
            // A typed terminal failure can explain why no response exists. Do
            // not append synthetic unknown answer/contract/utility owners for
            // downstream stages that never received a terminal answer.
            return causalLedger(observations, causes);
        }
        observations.add(answerObservation(origin));
        boolean contractSatisfied = responseOwnerContractSatisfied(
                facts.responseOwnerContract(), origin);
        boolean contractViolation = responseOwnerContractViolated(
                facts.responseOwnerContract(), origin);
        observations.add(new Cause(
                contractViolation
                        ? QualificationRecords.CausalOwner.RUNTIME_POLICY
                        : answerOwner(origin),
                QualificationRecords.CausalStage.RESPONSE_CONTRACT,
                contractViolation
                        ? QualificationRecords.CausalFact.RESPONSE_OWNER_CONTRACT_VIOLATED
                        : contractSatisfied
                                ? QualificationRecords.CausalFact.RESPONSE_OWNER_CONTRACT_SATISFIED
                                : QualificationRecords.CausalFact.RESPONSE_OWNER_CONTRACT_NOT_PROVEN,
                contractViolation
                        ? QualificationRecords.CausalReason.RESPONSE_OWNER_CONTRACT_VIOLATED
                        : contractSatisfied
                                ? QualificationRecords.CausalReason.OUTCOME_SATISFIED
                                : QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                "trace.json#terminal-answer-origin"));
        if (facts.verifierRejected()) {
            observations.add(new Cause(
                    QualificationRecords.CausalOwner.VERIFIER,
                    QualificationRecords.CausalStage.VERIFICATION,
                    QualificationRecords.CausalFact.VERIFIER_REJECTION_PROVEN,
                    facts.requirementsPass()
                            ? QualificationRecords.CausalReason
                                    .VERIFIER_DIVERGED_FROM_CANONICAL_REQUIREMENTS
                            : QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                    "trace.json#verification"));
        }
        boolean answerOwnedRequirementResult = facts.requirementsPass()
                || facts.answerOnlyRequirementFailure();
        observations.add(new Cause(
                answerOwnedRequirementResult
                        ? answerOwner(origin)
                        : QualificationRecords.CausalOwner.UNKNOWN,
                QualificationRecords.CausalStage.UTILITY,
                facts.requirementsPass()
                        ? QualificationRecords.CausalFact.CANONICAL_REQUIREMENTS_PASSED
                        : answerOwnedRequirementResult
                                ? QualificationRecords.CausalFact.CANONICAL_REQUIREMENTS_FAILED
                                : QualificationRecords.CausalFact
                                        .REQUIREMENT_DOMAIN_CAUSAL_OWNER_NOT_PROVEN,
                answerOwnedRequirementResult
                        ? utilityReason(facts, origin)
                        : QualificationRecords.CausalReason
                                .REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN,
                answerOwnedRequirementResult
                        ? "answer.txt#canonical-requirements"
                        : "mechanical-requirements.json#failed-non-answer-domains"));
        observations.add(new Cause(
                QualificationRecords.CausalOwner.RUNTIME_POLICY,
                QualificationRecords.CausalStage.UTILITY,
                facts.terminalPass()
                        ? QualificationRecords.CausalFact.EXPECTED_TERMINAL_STATE_PASSED
                        : QualificationRecords.CausalFact.EXPECTED_TERMINAL_STATE_FAILED,
                facts.terminalPass()
                        ? QualificationRecords.CausalReason.OUTCOME_SATISFIED
                        : QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                "trace.json#outcome"));
        return causalLedger(observations, causes);
    }

    private static List<QualificationRecords.CausalLedgerEntry> causalLedger(
            List<Cause> observations,
            List<Cause> causes) {
        List<QualificationRecords.CausalLedgerEntry> ledger = new ArrayList<>();
        List<Cause> unmatchedFirstCauses = new ArrayList<>(causes);
        for (Cause observation : observations) {
            int firstCauseIndex = unmatchedFirstCauses.indexOf(observation);
            boolean firstCause = firstCauseIndex >= 0;
            if (firstCause) unmatchedFirstCauses.remove(firstCauseIndex);
            ledger.add(new QualificationRecords.CausalLedgerEntry(
                    ledger.size() + 1,
                    observation.stage(),
                    observation.owner(),
                    observation.fact(),
                    observation.reason(),
                    observation.evidenceReference(),
                    firstCause));
        }
        return List.copyOf(ledger);
    }

    private static void addTrustObservations(
            List<Cause> out,
            QualificationRecords.TrustGates trust) {
        if (trust.passed()) {
            out.add(new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.TRUST_GATE,
                    QualificationRecords.CausalFact.TRUST_GATES_PASSED,
                    QualificationRecords.CausalReason.OUTCOME_SATISFIED,
                    "run-root#derived-trust-gates"));
            return;
        }
        out.addAll(trustCauses(trust));
        if (!trust.modelIdentityAgreement()) {
            out.add(new Cause(
                    QualificationRecords.CausalOwner.UNKNOWN,
                    QualificationRecords.CausalStage.TRUST_GATE,
                    QualificationRecords.CausalFact
                            .TRUST_GATE_MODEL_IDENTITY_AGREEMENT_FAILED,
                    QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                    "calibration-rules.json#alias+trace.json#model"));
        }
    }

    private static Cause answerObservation(QualificationRecords.AnswerOrigin origin) {
        return switch (origin) {
            case MODEL_PROVIDER -> new Cause(
                    QualificationRecords.CausalOwner.MODEL,
                    QualificationRecords.CausalStage.ANSWER_ORIGIN,
                    QualificationRecords.CausalFact.MODEL_RESPONSE_HASH_MATCHED,
                    QualificationRecords.CausalReason.OUTCOME_SATISFIED,
                    "trace.json#GENERATION_RESPONSE_CAPTURED+GENERATION_COMPLETED"
                            + "+TERMINAL_ANSWER_SELECTED+answer.txt");
            case RUNTIME_DETERMINISTIC, TOOL_DETERMINISTIC -> new Cause(
                    QualificationRecords.CausalOwner.RUNTIME_POLICY,
                    QualificationRecords.CausalStage.ANSWER_ORIGIN,
                    QualificationRecords.CausalFact.DETERMINISTIC_ANSWER_HASH_MATCHED,
                    QualificationRecords.CausalReason.OUTCOME_SATISFIED,
                    "trace.json#DETERMINISTIC_ANSWER_SELECTED+answer.txt");
            case OPERATOR -> new Cause(
                    QualificationRecords.CausalOwner.OPERATOR,
                    QualificationRecords.CausalStage.ANSWER_ORIGIN,
                    QualificationRecords.CausalFact.OPERATOR_INTERRUPT_AND_ABORT_PROVEN,
                    QualificationRecords.CausalReason.OPERATOR_INTERRUPTED,
                    "trace.json#DETERMINISTIC_ANSWER_SELECTED[origin=OPERATOR]"
                            + "+transcript.raw.json#user-interrupt+terminal-outcome");
            case NONE -> new Cause(
                    QualificationRecords.CausalOwner.UNKNOWN,
                    QualificationRecords.CausalStage.ANSWER_ORIGIN,
                    QualificationRecords.CausalFact.NO_TERMINAL_ANSWER_MATCHED,
                    QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                    "trace.json#terminal-answer-origin");
        };
    }

    private static QualificationRecords.CausalReason utilityReason(
            MechanicalFacts facts,
            QualificationRecords.AnswerOrigin origin) {
        if (facts.requirementsPass()) return QualificationRecords.CausalReason.OUTCOME_SATISFIED;
        return switch (origin) {
            case MODEL_PROVIDER -> QualificationRecords.CausalReason.MODEL_RESPONSE_FAILED_REQUIREMENT;
            case RUNTIME_DETERMINISTIC, TOOL_DETERMINISTIC -> QualificationRecords.CausalReason
                    .RUNTIME_DETERMINISTIC_ANSWER_FAILED_REQUIREMENT;
            case OPERATOR -> QualificationRecords.CausalReason.OPERATOR_INTERRUPTED;
            case NONE -> QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN;
        };
    }

    private static QualificationRecords.AnswerOrigin answerOrigin(MechanicalFacts facts) {
        if (facts.operatorInterrupted()) return QualificationRecords.AnswerOrigin.OPERATOR;
        if (facts.modelAnswerMatched()) return QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
        if (facts.toolDeterministicAnswerPresent()) {
            return QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC;
        }
        if (facts.deterministicAnswerPresent()) {
            return QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC;
        }
        return QualificationRecords.AnswerOrigin.NONE;
    }

    private static QualificationRecords.CausalOwner answerOwner(
            QualificationRecords.AnswerOrigin origin) {
        return switch (origin) {
            case MODEL_PROVIDER -> QualificationRecords.CausalOwner.MODEL;
            case RUNTIME_DETERMINISTIC, TOOL_DETERMINISTIC ->
                    QualificationRecords.CausalOwner.RUNTIME_POLICY;
            case OPERATOR -> QualificationRecords.CausalOwner.OPERATOR;
            case NONE -> QualificationRecords.CausalOwner.UNKNOWN;
        };
    }

    private static boolean responseOwnerContractViolated(
            QualificationRecords.ResponseOwnerContract contract,
            QualificationRecords.AnswerOrigin origin) {
        return origin != QualificationRecords.AnswerOrigin.NONE
                && !responseOwnerContractSatisfied(contract, origin);
    }

    static boolean responseOwnerContractSatisfied(
            QualificationRecords.ResponseOwnerContract contract,
            QualificationRecords.AnswerOrigin origin) {
        return switch (contract) {
            case MODEL_SYNTHESIS_REQUIRED ->
                    origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
            case DETERMINISTIC_RUNTIME_ALLOWED ->
                    origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                            || origin == QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC
                            || origin == QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC;
            case OPERATOR_OWNED -> origin == QualificationRecords.AnswerOrigin.OPERATOR;
        };
    }

    private static QualificationRecords.FailureClass failureClass(
            QualificationRecords.CausalOwner owner) {
        return switch (owner) {
            case MODEL -> QualificationRecords.FailureClass.MODEL_FAILURE;
            case RUNTIME_POLICY, VERIFIER -> QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT;
            case HARDWARE -> QualificationRecords.FailureClass.HARDWARE_FAILURE;
            case OPERATOR -> QualificationRecords.FailureClass.OPERATOR_INTERRUPTION;
            case PROVIDER, HARNESS, MIXED, UNKNOWN ->
                    QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE;
        };
    }

    private static Set<RequirementDomain> defaultFailedRequirementDomains(
            boolean requirementsPass) {
        return requirementsPass
                ? Set.of()
                : Set.of(RequirementDomain.ANSWER);
    }

    private static void validateFirstCauses(
            QualificationRecords.FailureClass failureClass,
            QualificationRecords.CausalOwner owner,
            QualificationRecords.CausalReason reason,
            List<QualificationRecords.CausalLedgerEntry> ledger) {
        List<QualificationRecords.CausalLedgerEntry> first = ledger.stream()
                .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                .toList();
        if (failureClass == QualificationRecords.FailureClass.NONE) {
            if (!first.isEmpty()) {
                throw new IllegalArgumentException("successful causal document cannot have a first cause");
            }
            return;
        }
        if (owner == QualificationRecords.CausalOwner.UNKNOWN) {
            if (!first.isEmpty()) {
                throw new IllegalArgumentException("unknown cause cannot contain a forged first cause");
            }
            return;
        }
        if (owner == QualificationRecords.CausalOwner.MIXED) {
            long distinctOwners = first.stream().map(QualificationRecords.CausalLedgerEntry::owner)
                    .distinct().count();
            if (first.size() < 2 || distinctOwners < 2
                    || first.stream().anyMatch(entry ->
                            entry.owner() == QualificationRecords.CausalOwner.MIXED
                                    || entry.owner() == QualificationRecords.CausalOwner.UNKNOWN)
                    || reason != QualificationRecords.CausalReason.MULTIPLE_CONTRIBUTING_OWNERS) {
                throw new IllegalArgumentException(
                        "mixed cause requires concrete contributing co-first causes");
            }
            return;
        }
        if (first.size() != 1
                || first.getFirst().owner() != owner
                || first.getFirst().reasonCode() != reason) {
            throw new IllegalArgumentException(
                    "known failure requires one concrete matching first cause");
        }
    }

    private static boolean verifierRejected(String status) {
        String normalized = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        return Set.of("FAILED", "ERROR", "MISMATCH", "REJECTED").contains(normalized);
    }

    static boolean hardwareExhausted(
            boolean typedResourceExhaustion,
            QualificationRecords.ResourceEvidence resources,
            long physicalRamBytes) {
        if (!typedResourceExhaustion || resources == null) return false;
        boolean vramSaturated = atLeastPercent(
                resources.gpuVramUsedBytes(), resources.gpuVramBytes(), 98);
        boolean ramSaturated = atLeastPercent(
                resources.processWorkingSetBytes(), physicalRamBytes, 98);
        return vramSaturated || ramSaturated;
    }

    static boolean atLeastPercent(long used, long total, int percent) {
        if (used < 0 || total <= 0 || percent < 0 || percent > 100) return false;
        long quotient = total / 100;
        long remainder = total % 100;
        long threshold = quotient * percent
                + (remainder * percent + 99) / 100;
        return used >= threshold;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    private static String nonblank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static void requireWritableProtocol(int protocolVersion) {
        if (protocolVersion != WRITABLE_PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "causal schema v2 finalization refuses protocol v"
                            + protocolVersion + "; exactly protocol v"
                            + WRITABLE_PROTOCOL_VERSION + " is writable");
        }
    }
}
