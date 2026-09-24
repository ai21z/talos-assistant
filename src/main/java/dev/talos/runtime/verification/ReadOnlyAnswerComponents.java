package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Required content is carried independently of provider prose and execution authority. */
public record ReadOnlyAnswerComponents(List<Component> components, boolean requestCovered,
                                     CoverageOrigin coverageOrigin) {
    static final int MAX_COMPONENT_TEXT_CHARS = 8192;
    public static final String FOOTER = "No files were changed. No browser or tests were run by this static diagnosis.";
    private static final String HEADING = "The answer was derived from retained source observations. Bounded mathematical results are identified separately.";
    public enum CoverageOrigin { LEGACY_PARSED, INTERPRETED, NATIVE_LEDGER, NATIVE_LEDGER_WITH_INTERPRETATIONS, REQUEST_MEANING }
    public enum Support { ESTABLISHED, ATTRIBUTED_INTERPRETATION, CONTRADICTED, AMBIGUOUS, INSUFFICIENT_EVIDENCE, UNSUPPORTED }
    public record Reference(String observationId, String textHash, int start, int end) {
        public Reference {
            Objects.requireNonNull(observationId);
            Objects.requireNonNull(textHash);
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source span.");
        }
    }
    public record Component(String requirementId, String kind, Support support, String reason,
                            String text, List<Reference> references, String derivationHash,
                            ObservationPresentation observationPresentation,
                            ReadOnlyAnswerInterpretation.Decision interpretationDecision) {
        public Component {
            Objects.requireNonNull(requirementId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(support);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(text);
            references = List.copyOf(references);
            derivationHash = derivationHash == null ? "" : derivationHash;
            if (references.size() > 8 || text.length() > MAX_COMPONENT_TEXT_CHARS) throw new IllegalArgumentException("Component limit exceeded.");
            if (observationPresentation != null && (!kind.equals("OBSERVATION")
                    || support != Support.ATTRIBUTED_INTERPRETATION))
                throw new IllegalArgumentException("Unsupported observation presentation.");
            if (interpretationDecision != null && (!kind.equals("UNKNOWN") || support != Support.UNSUPPORTED))
                throw new IllegalArgumentException("Unsupported interpretation decision.");
        }
        public Component(String requirementId, String kind, Support support, String reason,
                         String text, List<Reference> references, String derivationHash) {
            this(requirementId, kind, support, reason, text, references, derivationHash, null, null);
        }
        public boolean met() { return support == Support.ESTABLISHED || support == Support.ATTRIBUTED_INTERPRETATION; }
    }
    public record ObservationPresentation(String evidenceGroupId, String relationKind, String regionId,
                                          String sourceText) {
        public ObservationPresentation {
            Objects.requireNonNull(sourceText);
            if (!Objects.requireNonNull(evidenceGroupId).matches("[a-f0-9]{64}")
                    || !Objects.requireNonNull(relationKind).matches("DIAGNOSIS|SELECTOR|SCRIPT_DISTINCTION")
                    || !Objects.requireNonNull(regionId).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}")
                    || sourceText.isEmpty() || sourceText.length() > MAX_COMPONENT_TEXT_CHARS)
                throw new IllegalArgumentException("Invalid observation presentation.");
        }
    }
    public ReadOnlyAnswerComponents {
        components = List.copyOf(components);
        Objects.requireNonNull(coverageOrigin);
        if (components.size() > 32) throw new IllegalArgumentException("Requirement limit exceeded.");
    }
    public ReadOnlyAnswerComponents(List<Component> components, boolean requestCovered) {
        this(components, requestCovered, CoverageOrigin.LEGACY_PARSED);
    }
    public static ReadOnlyAnswerComponents empty() { return new ReadOnlyAnswerComponents(List.of(), false); }
    public boolean active() { return !components.isEmpty(); }
    public boolean complete() { return active() && requestCovered && components.stream().allMatch(Component::met); }
    public String render() { return render(components, List.of()); }
    public boolean deliveredIntact(String answer) { return complete() && render().equals(answer); }

    /** Privacy shaping is applied once and removed content is never restored. */
    public String present(String answer, UnaryOperator<String> sanitizer, long maximum) {
        String safe = Objects.toString(sanitizer.apply(answer), "");
        int cap = (int) Math.min(Integer.MAX_VALUE, Math.max(0, maximum));
        if (!render().equals(answer)) {
            return safe.length() <= cap ? safe : incomplete(cap);
        }
        if (!safe.equals(answer)) {
            String withheld = ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX
                    + "\n\nRequired answer content was changed by presentation policy.\n\n" + safe;
            return withheld.length() <= cap ? withheld : incomplete(cap);
        }
        if (safe.length() <= cap) return safe;
        List<Component> retained = new ArrayList<>(components);
        List<String> omitted = new ArrayList<>();
        while (!retained.isEmpty()) {
            Component removed = retained.removeLast();
            omitted.add(removed.kind());
            String bounded = render(retained, omitted);
            if (bounded.length() <= cap) return bounded;
        }
        return incomplete(cap);
    }

    private String render(List<Component> retained, List<String> omitted) {
        StringBuilder answer = new StringBuilder();
        if (!complete() || !omitted.isEmpty()) answer.append(ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX).append("\n\n");
        answer.append(HEADING);
        if (coverageOrigin == CoverageOrigin.INTERPRETED)
            answer.append(" The request and formula relevance were interpreted by the model. References and bounded calculations were checked separately, not treated as proof of meaning.");
        if (coverageOrigin == CoverageOrigin.NATIVE_LEDGER)
            answer.append(" Required results were identified by a bounded request parser. Formula relevance remains an interpretation. References, result roles and bounded calculations were checked separately, not treated as proof of meaning.");
        if (coverageOrigin == CoverageOrigin.NATIVE_LEDGER_WITH_INTERPRETATIONS)
            answer.append(" The original required results and unresolved regions were retained by a bounded request parser. Interpretation decisions were recorded separately. Any selected source observations were checked, but their relevance to unresolved regions and complete request coverage were not established.");
        if (coverageOrigin == CoverageOrigin.REQUEST_MEANING)
            answer.append(" Request roles were identified by bounded request-only rules. Unsupported meaning remains unresolved. Source references and calculations were checked separately, and formula relevance remains an interpretation.");
        appendComponents(answer, retained);
        if (!requestCovered) answer.append("\n\nA complete interpretation of the request was not established.");
        if (!omitted.isEmpty()) answer.append("\n\nRequired components were omitted by the output limit. ")
                .append(String.join(", ", omitted)).append('.');
        return answer.append("\n\n").append(FOOTER).toString();
    }

    private static void appendComponents(StringBuilder answer, List<Component> retained) {
        var groups = new LinkedHashMap<String, List<Component>>();
        for (var component : retained) {
            var presentation = component.observationPresentation();
            if (presentation != null) groups.computeIfAbsent(presentation.evidenceGroupId(), key -> new ArrayList<>()).add(component);
        }
        var emitted = new java.util.HashSet<String>();
        for (var component : retained) {
            var presentation = component.observationPresentation();
            if (presentation == null || groups.get(presentation.evidenceGroupId()).size() == 1) {
                answer.append("\n\n").append(component.text());
                continue;
            }
            var group = groups.get(presentation.evidenceGroupId());
            boolean consistent = group.stream().allMatch(value ->
                    value.observationPresentation().relationKind().equals(presentation.relationKind())
                    && value.observationPresentation().sourceText().equals(presentation.sourceText())
                    && value.references().equals(component.references()));
            if (!consistent) {
                answer.append("\n\n").append(component.text());
                continue;
            }
            if (!emitted.add(presentation.evidenceGroupId())) continue;
            String type = switch (presentation.relationKind()) {
                case "DIAGNOSIS" -> "diagnosis";
                case "SELECTOR" -> "selector correction";
                default -> "script distinction";
            };
            answer.append("\n\nAn additional ").append(type)
                    .append(" source observation was selected through model interpretation for unresolved request regions ")
                    .append(String.join(", ", group.stream().map(value -> "`" + value.observationPresentation().regionId() + "`").toList()))
                    .append(". Its relevance to those regions was not established. ").append(presentation.sourceText());
        }
    }

    private static String incomplete(int maximum) {
        String text = "[Answer incomplete]";
        return text.length() <= maximum ? text : "";
    }
}
