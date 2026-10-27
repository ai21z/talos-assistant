package dev.talos.runtime;

import dev.talos.core.util.Hash;
import dev.talos.runtime.verification.ReadOnlyAnswerComponents;
import dev.talos.runtime.verification.ReadOnlyAnswerInterpretation;
import dev.talos.safety.ContentRestrictions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

/** Historical producer associations are retained without a fresh evidence lease.
 * Presentation and history projection equality do not certify listener publication.
 */
public record TypedResultSettlement(UUID turnId, String requestHash,
        ReadOnlyAnswerComponents.CoverageOrigin coverageOrigin, boolean requestCovered,
        List<Entry> results, Loss loss, boolean presentationIntact, boolean historyProjectionIntact,
        ContentRestrictions contentRestrictions) {
    static final int MAX_CHARACTERS = 64_000;
    public enum Loss { NONE, NO_TYPED_RESULTS, BINDING_UNAVAILABLE, CONTENT_WITHHELD,
        PRESENTATION_CHANGED, RETENTION_CHANGED,
        LIMIT_EXCEEDED, TERMINAL_UNAVAILABLE }

    public record Entry(String requirementKey, String requirementId, String kind,
            ReadOnlyAnswerComponents.Support support, String reason, boolean reasonRetained,
            ReadOnlyAnswerInterpretation.Decision interpretationDecision, String text, boolean textRetained,
            List<ReadOnlyAnswerComponents.Reference> references, String derivationHash) {
        public Entry {
            Objects.requireNonNull(requirementKey);
            Objects.requireNonNull(requirementId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(support);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(text);
            references = List.copyOf(references);
            Objects.requireNonNull(derivationHash);
            if (!reasonRetained && !reason.isEmpty() || !textRetained && !text.isEmpty())
                throw new IllegalArgumentException("Unavailable component payload cannot be retained.");
        }

        private Entry withheld() {
            return new Entry(requirementKey, "", "", support, "", false, interpretationDecision,
                    "", false, List.of(), "");
        }

        long characters() {
            return (long) requirementKey.length() + requirementId.length() + kind.length() + reason.length() + text.length()
                    + derivationHash.length() + references.stream().mapToLong(reference ->
                        (long) reference.observationId().length() + reference.textHash().length()).sum();
        }
    }

    public TypedResultSettlement {
        Objects.requireNonNull(turnId);
        Objects.requireNonNull(requestHash);
        Objects.requireNonNull(coverageOrigin);
        results = List.copyOf(results);
        Objects.requireNonNull(loss);
        Objects.requireNonNull(contentRestrictions);
        if (!requestHash.isEmpty() && !requestHash.matches("[a-f0-9]{64}") || results.size() > 32
                || requestHash.length() + results.stream().mapToLong(Entry::characters).sum() > MAX_CHARACTERS
                || results.stream().anyMatch(entry -> !entry.requirementKey().matches("[a-f0-9]{64}")
                    || loss == Loss.NONE && !bounded(entry)))
            throw new IllegalArgumentException("Typed result retention limit exceeded.");
        if (loss != Loss.NONE && results.stream().anyMatch(entry -> !entry.requirementId().isEmpty()
                || !entry.kind().isEmpty() || !entry.reason().isEmpty() || !entry.text().isEmpty() || !entry.references().isEmpty()
                || !entry.derivationHash().isEmpty()))
            throw new IllegalArgumentException("Unavailable result content cannot be retained.");
        if (loss == Loss.NONE && (!contentRestrictions.historyAllowed() || !contentRestrictions.artifactAllowed()))
            throw new IllegalArgumentException("Private result content cannot be retained.");
    }

    public static TypedResultSettlement none() {
        return new TypedResultSettlement(new UUID(0, 0), "",
                ReadOnlyAnswerComponents.CoverageOrigin.LEGACY_PARSED, false,
                List.of(), Loss.NO_TYPED_RESULTS, false, false, ContentRestrictions.UNRESTRICTED);
    }

    static TypedResultSettlement settle(UUID turnId, String request, String currentRequest,
            ReadOnlyAnswerComponents components, String delivered, String retained,
            ContentRestrictions restrictions) {
        if (components == null || !components.active()) return none();
        String requestHash = hash(Objects.toString(request, ""));
        String rendered = components.render();
        var entries = IntStream.range(0, components.components().size()).mapToObj(index -> {
            var component = components.components().get(index);
            boolean textRetained = rendered.contains(component.text());
            boolean reasonRetained = !component.reason().isEmpty() && rendered.contains(component.reason());
            return new Entry(hash(turnId + "\n" + requestHash + "\n" + index + "\n" + component.requirementId()),
                    component.requirementId(), component.kind(), component.support(),
                    reasonRetained ? component.reason() : "", reasonRetained, component.interpretationDecision(),
                    textRetained ? component.text() : "", textRetained,
                    component.references(), component.derivationHash());
        }).toList();
        Loss loss = Loss.NONE;
        boolean presentationIntact = rendered.equals(delivered);
        boolean historyProjectionIntact = presentationIntact && rendered.equals(retained);
        if (request == null || request.isBlank() || !request.equals(currentRequest)
                || entries.stream().map(Entry::requirementId).distinct().count() != entries.size())
            loss = Loss.BINDING_UNAVAILABLE;
        else if (!restrictions.historyAllowed() || !restrictions.artifactAllowed())
            loss = Loss.CONTENT_WITHHELD;
        else if (!presentationIntact) loss = Loss.PRESENTATION_CHANGED;
        else if (!historyProjectionIntact) loss = Loss.RETENTION_CHANGED;
        else if (entries.stream().anyMatch(entry -> !bounded(entry))
                || requestHash.length() + entries.stream().mapToLong(Entry::characters).sum() > MAX_CHARACTERS)
            loss = Loss.LIMIT_EXCEEDED;
        if (loss != Loss.NONE) entries = entries.stream().map(Entry::withheld).toList();
        return new TypedResultSettlement(turnId,
                loss == Loss.CONTENT_WITHHELD || loss == Loss.BINDING_UNAVAILABLE ? "" : requestHash,
                components.coverageOrigin(), components.requestCovered(), entries, loss,
                presentationIntact, historyProjectionIntact, restrictions);
    }

    private static boolean bounded(Entry entry) {
        return entry.requirementId().length() <= 160 && entry.kind().length() <= 80
                && entry.reason().length() <= 120 && entry.text().length() <= 8192 && entry.derivationHash().length() <= 64
                && entry.references().size() <= 8 && entry.references().stream().allMatch(reference ->
                    reference.observationId().length() <= 160 && reference.textHash().matches("[a-f0-9]{64}"));
    }

    public TypedResultSettlement unavailable(Loss reason) {
        if (loss == Loss.NO_TYPED_RESULTS) return this;
        if (reason == Loss.NONE || reason == Loss.NO_TYPED_RESULTS)
            throw new IllegalArgumentException("A result loss reason is required.");
        return new TypedResultSettlement(turnId, reason == Loss.CONTENT_WITHHELD ? "" : requestHash,
                coverageOrigin, requestCovered, results.stream().map(Entry::withheld).toList(),
                reason, false, false, contentRestrictions);
    }

    TypedResultSettlement restricted(ContentRestrictions restrictions) {
        var combined = contentRestrictions.intersect(restrictions);
        if (combined.equals(contentRestrictions)) return this;
        var result = new TypedResultSettlement(turnId, requestHash, coverageOrigin, requestCovered,
                results, loss, presentationIntact, historyProjectionIntact, contentRestrictions);
        if (!combined.historyAllowed() || !combined.artifactAllowed())
            result = result.unavailable(Loss.CONTENT_WITHHELD);
        return new TypedResultSettlement(result.turnId, result.requestHash, result.coverageOrigin,
                result.requestCovered, result.results, result.loss, result.presentationIntact,
                result.historyProjectionIntact, combined);
    }

    long characters() { return requestHash.length() + results.stream().mapToLong(Entry::characters).sum(); }

    private static String hash(String value) {
        return Hash.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override public String toString() {
        return "TypedResultSettlement[results=" + results.size() + ", loss=" + loss + "]";
    }
}
