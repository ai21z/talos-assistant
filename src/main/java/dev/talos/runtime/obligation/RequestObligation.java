package dev.talos.runtime.obligation;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.talos.runtime.policy.ProtectedContentPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic, serializable request facts shared by runtime and qualification.
 *
 * <p>A {@link DerivationStatus#NOT_MIGRATED} value is explicit evidence that the
 * compiler does not yet own a request shape. It must never be interpreted as a
 * weaker successful obligation.
 */
public record RequestObligation(
        int schemaVersion,
        DerivationStatus derivationStatus,
        ResponseOwner responseOwner,
        ActionKind actionKind,
        MutationScope mutationScope,
        List<String> requiredMutationTargets,
        List<String> requiredSourceTargets,
        List<String> forbiddenTargets,
        List<PreservationConstraint> preservationConstraints,
        OutputShape outputShape,
        List<VerificationPostcondition> verificationPostconditions
) {
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public RequestObligation {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported request-obligation schema version: " + schemaVersion);
        }
        if (derivationStatus == null) {
            throw new IllegalArgumentException("Request-obligation derivation status is required");
        }
        if (responseOwner == null) {
            throw new IllegalArgumentException("Request-obligation response owner is required");
        }
        if (actionKind == null) {
            throw new IllegalArgumentException("Request-obligation action kind is required");
        }
        if (mutationScope == null) {
            throw new IllegalArgumentException("Request-obligation mutation scope is required");
        }

        requiredMutationTargets = canonicalPaths(requiredMutationTargets);
        requiredSourceTargets = canonicalPaths(requiredSourceTargets);
        forbiddenTargets = canonicalPaths(forbiddenTargets);
        preservationConstraints = canonicalPreservationConstraints(preservationConstraints);
        outputShape = outputShape == null ? OutputShape.none() : outputShape;
        verificationPostconditions = canonicalVerificationPostconditions(
                verificationPostconditions);

        Set<String> contradictoryTargets = new HashSet<>(requiredMutationTargets);
        contradictoryTargets.retainAll(forbiddenTargets);
        if (!contradictoryTargets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Required mutation targets cannot also be forbidden: "
                            + new TreeSet<>(contradictoryTargets));
        }

        Set<String> mutationTargets = Set.copyOf(requiredMutationTargets);
        for (PreservationConstraint constraint : preservationConstraints) {
            requireMutationTarget(
                    mutationTargets,
                    constraint.target(),
                    "preservation constraint");
        }
        if (outputShape.kind() != OutputKind.NONE) {
            requireMutationTarget(mutationTargets, outputShape.target(), "output shape");
        }
        for (VerificationPostcondition postcondition : verificationPostconditions) {
            requireMutationTarget(
                    mutationTargets,
                    postcondition.target(),
                    "verification postcondition");
        }

        if (derivationStatus == DerivationStatus.COMPLETE) {
            validateComplete(
                    responseOwner,
                    actionKind,
                    mutationScope,
                    requiredMutationTargets,
                    requiredSourceTargets,
                    preservationConstraints,
                    outputShape,
                    verificationPostconditions);
        } else {
            validateNotMigrated(
                    responseOwner,
                    actionKind,
                    mutationScope,
                    preservationConstraints,
                    outputShape,
                    verificationPostconditions);
        }
    }

    public String canonicalJson() {
        return serializeCanonical(this);
    }

    /**
     * Redacted diagnostic projection. It is not the canonical obligation bytes;
     * {@code canonicalSha256} binds those full bytes without disclosing literals.
     */
    public Map<String, Object> redactedProjection() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("kind", outputShape.kind().name());
        output.put("target", diagnosticPath(outputShape.target()));
        output.put("labelSha256", sha256(outputShape.label()));
        output.put("labelChars", outputShape.label().length());
        output.put("statementSha256", sha256(outputShape.statement()));
        output.put("statementChars", outputShape.statement().length());

        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", schemaVersion);
        projection.put("derivationStatus", derivationStatus.name());
        projection.put("responseOwner", responseOwner.name());
        projection.put("actionKind", actionKind.name());
        projection.put("mutationScope", mutationScope.name());
        projection.put("requiredMutationTargets", diagnosticPaths(requiredMutationTargets));
        projection.put("requiredSourceTargets", diagnosticPaths(requiredSourceTargets));
        projection.put("forbiddenTargets", diagnosticPaths(forbiddenTargets));
        projection.put("preservationConstraints", preservationConstraints.stream()
                .map(constraint -> Map.of(
                        "kind", constraint.kind().name(),
                        "target", diagnosticPath(constraint.target())))
                .toList());
        projection.put("outputShape", Map.copyOf(output));
        projection.put("verificationPostconditions", verificationPostconditions.stream()
                .map(postcondition -> Map.of(
                        "kind", postcondition.kind().name(),
                        "target", diagnosticPath(postcondition.target())))
                .toList());
        projection.put("canonicalSha256", canonicalSha256());
        return Map.copyOf(projection);
    }

    public String redactedProjectionJson() {
        return serializeCanonical(redactedProjection());
    }

    private static String serializeCanonical(Object value) {
        try {
            return CANONICAL_JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize request obligation", e);
        }
    }

    public String canonicalSha256() {
        return sha256(canonicalJson());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash request obligation", e);
        }
    }

    private static void validateComplete(
            ResponseOwner responseOwner,
            ActionKind actionKind,
            MutationScope mutationScope,
            List<String> requiredMutationTargets,
            List<String> requiredSourceTargets,
            List<PreservationConstraint> preservationConstraints,
            OutputShape outputShape,
            List<VerificationPostcondition> verificationPostconditions
    ) {
        if (responseOwner != ResponseOwner.DETERMINISTIC_RUNTIME_ALLOWED) {
            throw new IllegalArgumentException(
                    "The current complete request-obligation schema requires deterministic runtime ownership");
        }
        if (mutationScope != MutationScope.ONLY_REQUIRED_TARGETS
                || requiredMutationTargets.size() != 1) {
            throw new IllegalArgumentException(
                    "A complete request obligation requires exactly one mutation target");
        }
        if (!requiredSourceTargets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-bearing obligations remain not migrated until source coverage is verified");
        }
        String target = requiredMutationTargets.getFirst();
        if (outputShape.kind() == OutputKind.NONE || !target.equals(outputShape.target())) {
            throw new IllegalArgumentException(
                    "A complete request obligation requires one same-target output shape");
        }
        switch (actionKind) {
            case APPEND_STATEMENT -> validateCompleteAppendStatement(
                    target,
                    preservationConstraints,
                    outputShape,
                    verificationPostconditions);
            case APPEND_SECTION -> validateCompleteAppendSection(
                    target,
                    preservationConstraints,
                    outputShape,
                    verificationPostconditions);
            case REPLACE_TEXT_PRESERVE_REST -> validateCompletePreserveRestReplacement(
                    target,
                    preservationConstraints,
                    outputShape,
                    verificationPostconditions);
            case NONE -> throw new IllegalArgumentException(
                    "A complete request obligation requires a supported action");
        }
    }

    private static void validateCompleteAppendStatement(
            String target,
            List<PreservationConstraint> preservationConstraints,
            OutputShape outputShape,
            List<VerificationPostcondition> verificationPostconditions
    ) {
        requireExactPreservation(
                preservationConstraints,
                PreservationKind.NORMALIZED_PRIOR_CONTENT,
                target,
                "A complete append-statement obligation requires exact same-target preservation");
        if (outputShape.kind() != OutputKind.APPEND_LABELED_STATEMENT) {
            throw new IllegalArgumentException(
                    "A complete append-statement obligation requires its labeled-statement output");
        }
        requireExactPostconditions(
                verificationPostconditions,
                target,
                List.of(
                        VerificationKind.MATCHING_STATEMENT_PRESENT_ONCE,
                        VerificationKind.MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
                        VerificationKind.STRUCTURED_MUTATION_EVIDENCE),
                "A complete append-statement obligation requires its exact three same-target postconditions");
    }

    private static void validateCompleteAppendSection(
            String target,
            List<PreservationConstraint> preservationConstraints,
            OutputShape outputShape,
            List<VerificationPostcondition> verificationPostconditions
    ) {
        requireExactPreservation(
                preservationConstraints,
                PreservationKind.NORMALIZED_PRIOR_CONTENT,
                target,
                "A complete append-section obligation requires exact same-target preservation");
        if (outputShape.kind() != OutputKind.APPEND_MARKDOWN_SECTION) {
            throw new IllegalArgumentException(
                    "A complete append-section obligation requires its Markdown-section output");
        }
        requireExactPostconditions(
                verificationPostconditions,
                target,
                List.of(
                        VerificationKind.MATCHING_SECTION_PRESENT_ONCE,
                        VerificationKind.MATCHING_SECTION_FINAL_DOCUMENT_SECTION,
                        VerificationKind.STRUCTURED_MUTATION_EVIDENCE),
                "A complete append-section obligation requires its exact three same-target postconditions");
    }

    private static void validateCompletePreserveRestReplacement(
            String target,
            List<PreservationConstraint> preservationConstraints,
            OutputShape outputShape,
            List<VerificationPostcondition> verificationPostconditions
    ) {
        requireExactPreservation(
                preservationConstraints,
                PreservationKind.UNCHANGED_CONTENT_AROUND_REPLACEMENT,
                target,
                "A complete preserve-rest replacement requires exact same-target preservation");
        if (outputShape.kind() != OutputKind.REPLACE_TEXT) {
            throw new IllegalArgumentException(
                    "A complete preserve-rest replacement requires its text-replacement output");
        }
        requireExactPostconditions(
                verificationPostconditions,
                target,
                List.of(
                        VerificationKind.REQUESTED_REPLACEMENT_EXACTLY_ONCE,
                        VerificationKind.OLD_TEXT_ABSENT,
                        VerificationKind.NEW_TEXT_PRESENT,
                        VerificationKind.STRUCTURED_MUTATION_EVIDENCE),
                "A complete preserve-rest replacement requires its exact four same-target postconditions");
    }

    private static void requireExactPreservation(
            List<PreservationConstraint> preservationConstraints,
            PreservationKind kind,
            String target,
            String message
    ) {
        if (!preservationConstraints.equals(List.of(
                new PreservationConstraint(kind, target)))) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireExactPostconditions(
            List<VerificationPostcondition> actual,
            String target,
            List<VerificationKind> expectedKinds,
            String message
    ) {
        List<VerificationPostcondition> expected = canonicalVerificationPostconditions(
                expectedKinds.stream()
                        .map(kind -> new VerificationPostcondition(kind, target))
                        .toList());
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void validateNotMigrated(
            ResponseOwner responseOwner,
            ActionKind actionKind,
            MutationScope mutationScope,
            List<PreservationConstraint> preservationConstraints,
            OutputShape outputShape,
            List<VerificationPostcondition> verificationPostconditions
    ) {
        if (responseOwner != ResponseOwner.UNKNOWN
                || actionKind != ActionKind.NONE
                || mutationScope != MutationScope.NONE
                || !preservationConstraints.isEmpty()
                || outputShape.kind() != OutputKind.NONE
                || !verificationPostconditions.isEmpty()) {
            throw new IllegalArgumentException(
                    "A not-migrated request obligation cannot claim derived semantics");
        }
    }

    private static void requireMutationTarget(
            Set<String> mutationTargets,
            String target,
            String owner
    ) {
        if (!mutationTargets.contains(target)) {
            throw new IllegalArgumentException(
                    "Request-obligation " + owner
                            + " target is not a required mutation target: " + target);
        }
    }

    private static List<String> canonicalPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) return List.of();
        TreeSet<String> canonical = new TreeSet<>();
        for (String path : paths) {
            canonical.add(normalizePath(path));
        }
        return List.copyOf(canonical);
    }

    private static List<PreservationConstraint> canonicalPreservationConstraints(
            List<PreservationConstraint> constraints
    ) {
        if (constraints == null || constraints.isEmpty()) return List.of();
        List<PreservationConstraint> canonical = new ArrayList<>(constraints);
        if (canonical.stream().anyMatch(constraint -> constraint == null)) {
            throw new IllegalArgumentException("Preservation constraints cannot contain null");
        }
        canonical.sort(Comparator
                .comparing((PreservationConstraint constraint) -> constraint.kind().name())
                .thenComparing(PreservationConstraint::target));
        return canonical.stream().distinct().toList();
    }

    private static List<VerificationPostcondition> canonicalVerificationPostconditions(
            List<VerificationPostcondition> postconditions
    ) {
        if (postconditions == null || postconditions.isEmpty()) return List.of();
        List<VerificationPostcondition> canonical = new ArrayList<>(postconditions);
        if (canonical.stream().anyMatch(postcondition -> postcondition == null)) {
            throw new IllegalArgumentException(
                    "Verification postconditions cannot contain null");
        }
        canonical.sort(Comparator
                .comparing((VerificationPostcondition postcondition) ->
                        postcondition.kind().name())
                .thenComparing(VerificationPostcondition::target));
        return canonical.stream().distinct().toList();
    }

    private static String normalizePath(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Request-obligation target cannot be null");
        }
        String normalized = raw.strip().replace('\\', '/');
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Request-obligation target cannot be blank");
        }
        if (normalized.startsWith("/")
                || normalized.matches("(?i)^[a-z]:.*")) {
            throw new IllegalArgumentException(
                    "Request-obligation target must be workspace-relative: " + raw);
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Request-obligation target contains an unsafe path segment: " + raw);
            }
        }
        return normalized;
    }

    private static String normalizeOptionalPath(String raw) {
        return raw == null || raw.isBlank() ? "" : normalizePath(raw);
    }

    public static String diagnosticPath(String path) {
        if (path == null || path.isBlank()) return "";
        return ProtectedContentPolicy.looksProtectedPathString(path)
                ? "<protected-path>"
                : path;
    }

    public static String canonicalTarget(String raw) {
        return normalizePath(raw);
    }

    private static List<String> diagnosticPaths(List<String> paths) {
        return paths == null
                ? List.of()
                : paths.stream().map(RequestObligation::diagnosticPath).toList();
    }

    public enum DerivationStatus {
        COMPLETE,
        NOT_MIGRATED
    }

    public enum ResponseOwner {
        DETERMINISTIC_RUNTIME_ALLOWED,
        MODEL_SYNTHESIS_REQUIRED,
        OPERATOR_OWNED,
        UNKNOWN
    }

    public enum ActionKind {
        APPEND_STATEMENT,
        APPEND_SECTION,
        REPLACE_TEXT_PRESERVE_REST,
        NONE
    }

    public enum MutationScope {
        ONLY_REQUIRED_TARGETS,
        NONE
    }

    public enum PreservationKind {
        NORMALIZED_PRIOR_CONTENT,
        UNCHANGED_CONTENT_AROUND_REPLACEMENT
    }

    public enum OutputKind {
        APPEND_LABELED_STATEMENT,
        APPEND_MARKDOWN_SECTION,
        REPLACE_TEXT,
        NONE
    }

    public enum VerificationKind {
        MATCHING_STATEMENT_PRESENT_ONCE,
        MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
        MATCHING_SECTION_PRESENT_ONCE,
        MATCHING_SECTION_FINAL_DOCUMENT_SECTION,
        REQUESTED_REPLACEMENT_EXACTLY_ONCE,
        OLD_TEXT_ABSENT,
        NEW_TEXT_PRESENT,
        STRUCTURED_MUTATION_EVIDENCE
    }

    public record PreservationConstraint(PreservationKind kind, String target) {
        public PreservationConstraint {
            if (kind == null) {
                throw new IllegalArgumentException("Preservation kind is required");
            }
            target = normalizePath(target);
        }
    }

    public record OutputShape(OutputKind kind, String target, String label, String statement) {
        public OutputShape {
            if (kind == null) {
                throw new IllegalArgumentException("Output kind is required");
            }
            target = normalizeOptionalPath(target);
            label = label == null ? "" : label.strip();
            statement = statement == null ? "" : statement.strip();
            if (kind == OutputKind.NONE) {
                if (!target.isBlank() || !label.isBlank() || !statement.isBlank()) {
                    throw new IllegalArgumentException(
                            "A NONE output shape cannot claim target or content");
                }
            } else if (target.isBlank() || label.isBlank() || statement.isBlank()) {
                throw new IllegalArgumentException(
                        "A typed output shape requires target and two non-blank content operands");
            }
        }

        public static OutputShape none() {
            return new OutputShape(OutputKind.NONE, "", "", "");
        }
    }

    public record VerificationPostcondition(VerificationKind kind, String target) {
        public VerificationPostcondition {
            if (kind == null) {
                throw new IllegalArgumentException("Verification kind is required");
            }
            target = normalizePath(target);
        }
    }
}
