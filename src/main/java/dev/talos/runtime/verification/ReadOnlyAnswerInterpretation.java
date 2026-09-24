package dev.talos.runtime.verification;

import java.util.Objects;

/** A proposed relation is retained without replacing its unresolved request region. */
public record ReadOnlyAnswerInterpretation(String requestSha256, String catalogId, String regionId,
                                         String relationId, String targetId, Decision decision) {
    public enum Decision { SELECTED, UNSUPPORTED, MISSING }

    public ReadOnlyAnswerInterpretation {
        require(requestSha256, "[a-f0-9]{64}");
        require(catalogId, "[a-f0-9]{64}");
        require(regionId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");
        Objects.requireNonNull(decision);
        require(relationId, decision == Decision.SELECTED ? "[A-Za-z0-9][A-Za-z0-9._:-]{0,79}" : "");
        require(targetId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");
    }

    public ReadOnlyAnswerInterpretation(String requestSha256, String catalogId, String regionId,
                                       String relationId, String targetId) {
        this(requestSha256, catalogId, regionId, relationId, targetId, Decision.SELECTED);
    }

    private static void require(String value, String pattern) {
        if (!Objects.requireNonNull(value).matches(pattern))
            throw new IllegalArgumentException("Invalid interpretation identity.");
    }
}
