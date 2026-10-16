package dev.talos.core.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-executing text cleanup for Talos tool-call protocol fragments.
 *
 * <p>This class may classify protocol-shaped JSON, but it never instantiates,
 * authorizes, or dispatches a tool call. It owns answer/sink cleanup for
 * places, such as RAG answers, where tool protocol text is never valid
 * user-facing prose but no runtime tool dispatcher exists.
 */
public final class ToolProtocolText {
    private static final int MAX_STRIP_FIXED_POINT_PASSES = 8;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private static final List<String> TOOL_NAME_KEYS = List.of(
            "\"name\"",
            "\"function\"",
            "\"function_name\"",
            "\"tool_name\"",
            "\"tool\""
    );

    /**
     * Bare Talos tool-call JSON at line boundaries. Candidate discovery uses
     * this pattern before the canonical selection result is consumed by the
     * runtime parser.
     *
     * <p>The quantifiers are possessive (T754). The alternation branches start with
     * disjoint characters (non-brace versus {@code '{'}), so at every position at
     * most one branch can consume; giving characters back can never produce a
     * different successful partition, and possessive matching accepts exactly the
     * same language while eliminating the exponential backtracking a long unclosed
     * candidate otherwise triggers on every model response. The first branch is
     * {@code ++} (not {@code *+}) deliberately: a zero-length first-branch iteration
     * terminates the loop, and under a possessive outer loop there is no backtrack
     * left to retry that iteration with the brace branch; {@code *+} silently stops
     * matching one-level nested argument objects. Any future edit to this
     * alternation must re-verify branch disjointness before keeping the possessive
     * form (pinned by the adversarial and nested-brace tests).
     */
    private static final Pattern BARE_JSON_PATTERN = Pattern.compile(
            "(?:^|\\n)\\s*(\\{\\s*\"(?:name|function|function_name|tool_name|tool)\"\\s*:\\s*\"talos\\.(?:[^{}]++|\\{[^{}]*+\\})*+\\})",
            Pattern.DOTALL
    );

    private static final Pattern TOOL_NAME_FIELD_PATTERN = Pattern.compile(
            "\"(?:name|function|function_name|tool_name|tool)\"\\s*:\\s*['\"]([^'\"]+)['\"]",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JSON_TOOL_NAME_STRING_START = Pattern.compile(
            "\"(?:name|function|function_name|tool_name|tool)\"\\s*:\\s*(\")"
    );

    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "<(?:tool_call|function_call|tool|function)>\\s*.*?\\s*</(?:tool_call|function_call|tool|function)>",
            Pattern.DOTALL
    );

    private ToolProtocolText() {}

    /** Text JSON envelope recognized by the executable fallback parser. */
    public enum JsonProtocolKind {
        FENCED_JSON,
        STANDALONE_JSON
    }

    /** Observational tail shapes, never executable identity or argument validation. */
    public enum SelectedFenceTailShape {
        COMPLETE_RECOGNIZED_OBJECT_SEQUENCE,
        COMPLETE_OTHER_JSON_SEQUENCE,
        MALFORMED_INCOMPLETE_OR_OTHER,
        INSPECTION_LIMIT_REACHED
    }

    // Aggregate bounds for one diagnostic selection, not parser acceptance limits.
    public static final int MAX_DIAGNOSTIC_FENCE_TAILS = 64;
    static final int MAX_DIAGNOSTIC_FENCE_TAIL_CHARS = 65_536;

    /**
     * Inspects complete affected tails once in source order. Only enum values escape.
     * A positive shape consumes every tail value, with JSON whitespace separating
     * it from the selected root and preceding values. Recognition uses the same
     * canonical identity rules as selection, not argument or authority validation.
     * Limits apply to the whole supplied selection, never independently per tail.
     */
    public static Set<SelectedFenceTailShape> inspectSelectedFenceTails(
            String source, List<SelectedExecutableJsonSpan> affectedFences) {
        if (source == null || affectedFences == null)
            return Set.of(SelectedFenceTailShape.MALFORMED_INCOMPLETE_OR_OTHER);
        if (affectedFences.size() > MAX_DIAGNOSTIC_FENCE_TAILS)
            return Set.of(SelectedFenceTailShape.INSPECTION_LIMIT_REACHED);
        long characters = 0;
        int previousEnd = -1;
        for (var fence : affectedFences) {
            if (fence == null || fence.kind() != JsonProtocolKind.FENCED_JSON
                    || fence.rootEndExclusive() < previousEnd
                    || fence.envelopeContentEndExclusive() > source.length())
                return Set.of(SelectedFenceTailShape.MALFORMED_INCOMPLETE_OR_OTHER);
            previousEnd = fence.envelopeContentEndExclusive();
            characters += fence.envelopeContentEndExclusive() - fence.rootEndExclusive();
            if (characters > MAX_DIAGNOSTIC_FENCE_TAIL_CHARS)
                return Set.of(SelectedFenceTailShape.INSPECTION_LIMIT_REACHED);
        }
        var shapes = java.util.EnumSet.noneOf(SelectedFenceTailShape.class);
        for (var fence : affectedFences) {
            shapes.add(inspectSelectedFenceTail(source.substring(
                    fence.rootEndExclusive(), fence.envelopeContentEndExclusive())));
        }
        return Set.copyOf(shapes);
    }

    private static SelectedFenceTailShape inspectSelectedFenceTail(String tail) {
        boolean any = false;
        boolean recognizedOnly = true;
        try (JsonParser parser = MAPPER.createParser(tail)) {
            while (parser.nextToken() != null) {
                long start = parser.currentTokenLocation().getCharOffset();
                // Checking the actual boundary also rejects adjacent object/string
                // roots accepted by a streaming parser without a separator.
                if (start <= 0 || start >= tail.length()
                        || !isJsonWhitespace(tail.charAt((int) start - 1)))
                    return SelectedFenceTailShape.MALFORMED_INCOMPLETE_OR_OTHER;
                JsonNode value = MAPPER.readTree(parser);
                if (value == null) return SelectedFenceTailShape.MALFORMED_INCOMPLETE_OR_OTHER;
                any = true;
                recognizedOnly &= value.isObject() && isRecognizedToolCallObject(value);
            }
            return !any ? SelectedFenceTailShape.MALFORMED_INCOMPLETE_OR_OTHER
                    : recognizedOnly ? SelectedFenceTailShape.COMPLETE_RECOGNIZED_OBJECT_SEQUENCE
                    : SelectedFenceTailShape.COMPLETE_OTHER_JSON_SEQUENCE;
        } catch (Exception | StackOverflowError invalidOrLimited) {
            // Diagnostic parsing must not replace or escape the existing rejection.
            return SelectedFenceTailShape.MALFORMED_INCOMPLETE_OR_OTHER;
        }
    }

    private static boolean isJsonWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    /**
     * Exact first JSON root selected by the text fallback parser, plus the
     * surrounding protocol envelope that carried it.
     *
     * <p>Root offsets are the only bytes eligible for exact preservation and
     * parser consumption. Envelope offsets exist solely for suppression and
     * fail-closed cleanup. This is classification evidence: selection never
     * authorizes, approves, or executes a proposal.
     */
    public record SelectedExecutableJsonSpan(
            int rootStartInclusive,
            int rootEndExclusive,
            int envelopeStartInclusive,
            int envelopeContentEndExclusive,
            int envelopeEndExclusive,
            JsonProtocolKind kind
    ) {
        public SelectedExecutableJsonSpan {
            if (envelopeStartInclusive < 0
                    || envelopeEndExclusive < envelopeStartInclusive
                    || rootStartInclusive < envelopeStartInclusive
                    || rootEndExclusive < rootStartInclusive
                    || envelopeContentEndExclusive < rootEndExclusive
                    || envelopeContentEndExclusive > envelopeEndExclusive
                    || rootEndExclusive > envelopeEndExclusive) {
                throw new IllegalArgumentException("invalid selected JSON span");
            }
            if (kind == null) {
                throw new IllegalArgumentException("selected JSON span kind is required");
            }
        }

        public String rootFrom(String source) {
            String value = source == null ? "" : source;
            if (rootEndExclusive > value.length()) {
                throw new IllegalArgumentException("selected JSON root exceeds source");
            }
            return value.substring(rootStartInclusive, rootEndExclusive);
        }
    }

    /**
     * One immutable selection decision with the two orderings required by its
     * consumers. Execution order is the parser's pinned fenced-first order;
     * source order is the same selected set sorted for spatial transformations.
     * Suppression-only envelopes are never exact-preservation or authority input.
     */
    public record JsonProposalSelection(
            List<SelectedExecutableJsonSpan> executionOrder,
            List<SelectedExecutableJsonSpan> sourceOrder,
            List<PotentialJsonEnvelopeSpan> suppressionOnlyEnvelopes,
            List<SelectedExecutableJsonSpan> recognizedCandidates
    ) {
        public JsonProposalSelection {
            executionOrder = List.copyOf(executionOrder);
            sourceOrder = List.copyOf(sourceOrder);
            suppressionOnlyEnvelopes = List.copyOf(suppressionOnlyEnvelopes);
            recognizedCandidates = List.copyOf(recognizedCandidates);
            if (executionOrder.size() != sourceOrder.size()
                    || !new HashSet<>(executionOrder).equals(new HashSet<>(sourceOrder))) {
                throw new IllegalArgumentException(
                        "execution and source order must contain the same selected spans");
            }
        }
    }

    /** Source interval excluded from one canonical selection pass. */
    public record SourceRange(int startInclusive, int endExclusive) {
        public SourceRange {
            if (startInclusive < 0 || endExclusive <= startInclusive) {
                throw new IllegalArgumentException("invalid source range");
            }
        }
    }

    /**
     * Full source range of a potentially executable JSON protocol envelope.
     *
     * <p>This conservative range exists only so display and sanitization code
     * can suppress rejected, duplicate, subordinate, or incomplete fenced and
     * standalone protocol without transforming it into a valid proposal. It
     * is never parser or execution-authority input.
     */
    public record PotentialJsonEnvelopeSpan(
            int startInclusive,
            int endExclusive,
            boolean closed
    ) {
        public PotentialJsonEnvelopeSpan {
            if (startInclusive < 0 || endExclusive < startInclusive) {
                throw new IllegalArgumentException(
                        "invalid potential JSON envelope span");
            }
        }
    }

    /**
     * The bare tool-call JSON pattern shared with the runtime text-fallback
     * parser, so the two detection surfaces cannot drift apart.
     */
    public static Pattern bareToolJsonPattern() {
        return BARE_JSON_PATTERN;
    }

    /** True only when the complete supplied value is one decodable JSON object. */
    public static boolean isCompleteJsonObjectValue(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty() || value.charAt(0) != '{') return false;
        try (JsonParser parser = MAPPER.createParser(value)) {
            JsonNode root = MAPPER.readTree(parser);
            return root != null
                    && root.isObject()
                    && parser.nextToken() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Produces the canonical JSON proposal selection consumed by both the text
     * fallback parser and exact-preserving sanitization.
     *
     * <p>Candidate discovery happens before selection, but only the returned
     * selected roots have executable identity. Pinned fenced-first priority,
     * the leading-adjacent compatibility pass, and exact-root deduplication are
     * owned here so downstream consumers cannot select different bytes.
     */
    public static JsonProposalSelection selectExecutableJsonProposals(String text) {
        return selectExecutableJsonProposalsOutside(text, List.of());
    }

    /**
     * Applies the same canonical selection after removing candidates whose
     * exact roots overlap an already-classified external source range.
     */
    public static JsonProposalSelection selectExecutableJsonProposalsOutside(
            String text,
            List<SourceRange> excludedRanges) {
        String value = text == null ? "" : text;
        if (value.isBlank()) {
            return new JsonProposalSelection(
                    List.of(), List.of(), List.of(), List.of());
        }

        List<JsonCandidateSpan> candidates = new ArrayList<>();
        List<JsonValueSpan> leadingAdjacentValues = new ArrayList<>();
        List<CodeFenceEnvelope> codeFenceEnvelopes = codeFenceEnvelopes(value);

        for (CodeFenceEnvelope envelope : codeFenceEnvelopes) {
            if (!envelope.jsonOpening() || !envelope.closed()) {
                continue;
            }

            int nameKeyEnd = firstRawToolNameKeyEnd(
                    value,
                    envelope.payloadStartInclusive(),
                    envelope.closeStartInclusive());
            if (nameKeyEnd < 0) {
                continue;
            }

            // Preserve the predecessor fence language without its cross-envelope
            // lazy match: the first triple marker after the raw name-key gate is
            // the only executable candidate close. In particular, a triple marker
            // after the name inside a JSON string fails closed; it is not retried
            // against a later outer marker.
            int candidateClose = value.indexOf("```", nameKeyEnd);
            if (candidateClose < 0
                    || candidateClose > envelope.closeStartInclusive()) {
                continue;
            }
            int payloadCandidateEnd = optionalLineBreakStartBefore(
                    value, candidateClose);
            int[] payload = strippedRange(
                    value,
                    envelope.payloadStartInclusive(),
                    payloadCandidateEnd);
            int rootEnd = payload[0] < payload[1]
                    ? findJsonLikeObjectEnd(value, payload[0], payload[1])
                    : -1;
            int rootEndExclusive = rootEnd < 0 ? -1 : rootEnd + 1;
            if (rootEndExclusive > payload[0]
                    && isRecognizedToolCallObject(readFirstJsonObject(
                            value.substring(payload[0], rootEndExclusive)))) {
                candidates.add(new JsonCandidateSpan(
                        payload[0],
                        rootEndExclusive,
                        envelope.startInclusive(),
                        envelope.closeStartInclusive(),
                        envelope.endExclusive(),
                        JsonProtocolKind.FENCED_JSON));
            }
        }

        collectStandaloneJsonSpans(
                value,
                codeFenceEnvelopes,
                candidates,
                leadingAdjacentValues);
        if (candidates.isEmpty()) {
            return new JsonProposalSelection(
                    List.of(),
                    List.of(),
                    suppressionOnlyJsonEnvelopes(value, List.of(), List.of()),
                    List.of());
        }

        candidates.sort(Comparator
                .comparingInt(JsonCandidateSpan::envelopeStartInclusive)
                .thenComparing(Comparator.comparingInt(
                        JsonCandidateSpan::envelopeEndExclusive).reversed()));

        List<JsonCandidateSpan> nonOverlapping = new ArrayList<>(candidates.size());
        int protectedThrough = -1;
        for (JsonCandidateSpan candidate : candidates) {
            if (candidate.envelopeStartInclusive() < protectedThrough) {
                continue;
            }
            nonOverlapping.add(candidate);
            protectedThrough = candidate.envelopeEndExclusive();
        }
        nonOverlapping = outsideExcludedRanges(
                nonOverlapping, excludedRanges);
        return buildJsonProposalSelection(
                value, nonOverlapping, leadingAdjacentValues);
    }

    private static List<JsonCandidateSpan> outsideExcludedRanges(
            List<JsonCandidateSpan> candidates,
            List<SourceRange> excludedRanges) {
        if (candidates.isEmpty()
                || excludedRanges == null
                || excludedRanges.isEmpty()) {
            return candidates;
        }
        List<SourceRange> excluded = excludedRanges.stream()
                .sorted(Comparator
                        .comparingInt(SourceRange::startInclusive)
                        .thenComparingInt(SourceRange::endExclusive))
                .toList();
        List<JsonCandidateSpan> retained = new ArrayList<>(candidates.size());
        int excludedIndex = 0;
        for (JsonCandidateSpan candidate : candidates) {
            while (excludedIndex < excluded.size()
                    && excluded.get(excludedIndex).endExclusive()
                    <= candidate.rootStartInclusive()) {
                excludedIndex++;
            }
            boolean overlaps = excludedIndex < excluded.size()
                    && excluded.get(excludedIndex).startInclusive()
                    < candidate.rootEndExclusive();
            if (!overlaps) retained.add(candidate);
        }
        return List.copyOf(retained);
    }

    /**
     * Returns the selected executable JSON roots in the parser's exact order.
     */
    public static List<SelectedExecutableJsonSpan> recognizedExecutableJsonSpans(
            String text) {
        return selectExecutableJsonProposals(text).executionOrder();
    }

    private static JsonProposalSelection buildJsonProposalSelection(
            String source,
            List<JsonCandidateSpan> candidatesInSourceOrder,
            List<JsonValueSpan> leadingAdjacentValues) {
        boolean hasFencedJson = candidatesInSourceOrder.stream().anyMatch(
                span -> span.kind() == JsonProtocolKind.FENCED_JSON);
        List<JsonCandidateSpan> selectedCandidates = new ArrayList<>();
        Set<String> consumedRoots = new HashSet<>();

        if (hasFencedJson) {
            for (JsonCandidateSpan candidate : candidatesInSourceOrder) {
                if (candidate.kind() == JsonProtocolKind.FENCED_JSON) {
                    addSelectedCandidate(
                            source, candidate, selectedCandidates, consumedRoots);
                }
            }

            Map<JsonValueSpan, JsonCandidateSpan> standaloneByRange =
                    new HashMap<>();
            for (JsonCandidateSpan candidate : candidatesInSourceOrder) {
                if (candidate.kind() == JsonProtocolKind.STANDALONE_JSON) {
                    standaloneByRange.put(
                            new JsonValueSpan(
                                    candidate.rootStartInclusive(),
                                    candidate.rootEndExclusive()),
                            candidate);
                }
            }

            int adjacentCursor = leadingJavaWhitespaceEnd(source);
            for (JsonValueSpan value : leadingAdjacentValues) {
                if (value.startInclusive() != adjacentCursor) {
                    break;
                }
                JsonCandidateSpan candidate = standaloneByRange.get(value);
                if (candidate != null) {
                    addSelectedCandidate(
                            source,
                            candidate,
                            selectedCandidates,
                            consumedRoots);
                }
                // Ordinary and duplicate values both consume their source
                // positions in the pinned unconditional adjacent-value pass.
                adjacentCursor = trailingJsonWhitespaceEnd(
                        source, value.endExclusive());
            }
        } else {
            for (JsonCandidateSpan candidate : candidatesInSourceOrder) {
                addSelectedCandidate(
                        source, candidate, selectedCandidates, consumedRoots);
            }
        }

        Set<JsonCandidateSpan> selectedSet = new HashSet<>(selectedCandidates);
        List<JsonCandidateSpan> unselectedCandidates = candidatesInSourceOrder.stream()
                .filter(candidate -> !selectedSet.contains(candidate))
                .toList();

        List<SelectedExecutableJsonSpan> executionOrder = selectedCandidates.stream()
                .map(JsonCandidateSpan::toSelected)
                .toList();
        List<SelectedExecutableJsonSpan> sourceOrder = executionOrder.stream()
                .sorted(Comparator
                        .comparingInt(SelectedExecutableJsonSpan::rootStartInclusive)
                        .thenComparingInt(
                                SelectedExecutableJsonSpan::rootEndExclusive))
                .toList();
        List<PotentialJsonEnvelopeSpan> suppressionOnly =
                suppressionOnlyJsonEnvelopes(
                        source, sourceOrder, unselectedCandidates);
        List<SelectedExecutableJsonSpan> recognizedCandidates =
                candidatesInSourceOrder.stream()
                        .map(JsonCandidateSpan::toSelected)
                        .toList();
        return new JsonProposalSelection(
                executionOrder,
                sourceOrder,
                suppressionOnly,
                recognizedCandidates);
    }

    private static void addSelectedCandidate(
            String source,
            JsonCandidateSpan candidate,
            List<JsonCandidateSpan> selected,
            Set<String> consumedRoots) {
        String root = source.substring(
                candidate.rootStartInclusive(), candidate.rootEndExclusive());
        if (consumedRoots.add(root)) {
            selected.add(candidate);
        }
    }

    private static List<PotentialJsonEnvelopeSpan> suppressionOnlyJsonEnvelopes(
            String source,
            List<SelectedExecutableJsonSpan> selectedInSourceOrder,
            List<JsonCandidateSpan> unselectedCandidates) {
        Set<PotentialJsonEnvelopeSpan> uniqueSuppression = new HashSet<>();
        for (JsonCandidateSpan candidate : unselectedCandidates) {
            uniqueSuppression.add(new PotentialJsonEnvelopeSpan(
                    candidate.envelopeStartInclusive(),
                    candidate.envelopeEndExclusive(),
                    true));
        }

        addUnselectedJsonWithinSelectedFences(
                uniqueSuppression, source, selectedInSourceOrder);
        addSuppressionOnlyPotentials(
                uniqueSuppression,
                potentialExecutableJsonEnvelopeSpans(source),
                selectedInSourceOrder);
        addSuppressionOnlyPotentials(
                uniqueSuppression,
                potentialExecutableStandaloneJsonSpans(source),
                selectedInSourceOrder);
        List<PotentialJsonEnvelopeSpan> suppression =
                new ArrayList<>(uniqueSuppression);
        suppression.sort(Comparator
                .comparingInt(PotentialJsonEnvelopeSpan::startInclusive)
                .thenComparing(Comparator.comparingInt(
                        PotentialJsonEnvelopeSpan::endExclusive).reversed()));
        return List.copyOf(suppression);
    }

    private static void addUnselectedJsonWithinSelectedFences(
            Set<PotentialJsonEnvelopeSpan> suppression,
            String source,
            List<SelectedExecutableJsonSpan> selectedInSourceOrder) {
        for (SelectedExecutableJsonSpan selected : selectedInSourceOrder) {
            if (selected.kind() != JsonProtocolKind.FENCED_JSON) {
                continue;
            }

            // A fenced proposal selects only its first JSON root. Any later
            // Talos-shaped object belongs to the protocol envelope, but it has
            // no executable identity. Suppress that complete subordinate
            // object so legacy HTML cleanup cannot leave a proposal-shaped
            // shell behind while protecting only the authoritative root.
            int tailStart = selected.rootEndExclusive();
            int tailEnd = Math.max(
                    tailStart, selected.envelopeContentEndExclusive());
            List<PotentialJsonEnvelopeSpan> subordinate = new ArrayList<>();
            collectPotentialStandaloneJsonSpans(
                    source, tailStart, tailEnd, true, subordinate);
            suppression.addAll(subordinate);
        }
    }

    private static void addSuppressionOnlyPotentials(
            Set<PotentialJsonEnvelopeSpan> suppression,
            List<PotentialJsonEnvelopeSpan> potentials,
            List<SelectedExecutableJsonSpan> selectedInSourceOrder) {
        int selectedIndex = 0;
        for (PotentialJsonEnvelopeSpan potential : potentials) {
            while (selectedIndex < selectedInSourceOrder.size()
                    && selectedInSourceOrder.get(selectedIndex).rootEndExclusive()
                    <= potential.startInclusive()) {
                selectedIndex++;
            }
            boolean overlapsSelected = selectedIndex < selectedInSourceOrder.size()
                    && selectedInSourceOrder.get(selectedIndex).rootStartInclusive()
                    < potential.endExclusive();
            if (!overlapsSelected) {
                suppression.add(potential);
            }
        }
    }

    /**
     * Finds closed or unclosed JSON fences whose available payload contains a
     * complete, decodable accepted/custom-Talos name field.
     *
     * <p>The result is deliberately broader than executable recognition. It
     * allows display/sanitization owners to suppress rejected protocol
     * atomically so removal of trailing tags cannot manufacture a valid tool
     * proposal. These spans must never authorize, approve, or execute tools.
     */
    public static List<PotentialJsonEnvelopeSpan> potentialExecutableJsonEnvelopeSpans(
            String text) {
        String value = text == null ? "" : text;
        if (value.isBlank()) return List.of();

        List<PotentialJsonEnvelopeSpan> spans = new ArrayList<>();
        for (CodeFenceEnvelope envelope : codeFenceEnvelopes(value)) {
            if (!envelope.jsonOpening()) {
                continue;
            }
            int payloadEnd = envelope.closed()
                    ? envelope.closeStartInclusive()
                    : value.length();
            if (hasPotentialExecutableToolIdentity(
                    value.substring(envelope.payloadStartInclusive(), payloadEnd))) {
                spans.add(new PotentialJsonEnvelopeSpan(
                        envelope.startInclusive(),
                        envelope.endExclusive(),
                        envelope.closed()));
            }
        }
        return List.copyOf(spans);
    }

    /**
     * Finds balanced or truncated standalone JSON-like objects that carry a
     * potential Talos tool identity but are not executable protocol.
     *
     * <p>These immutable ranges are display/sanitization evidence only. They
     * deliberately exclude every closed or unclosed code-fence envelope, and
     * callers must never use them to authorize or execute a proposal.
     */
    public static List<PotentialJsonEnvelopeSpan>
            potentialExecutableStandaloneJsonSpans(String text) {
        String value = text == null ? "" : text;
        if (value.isBlank()) return List.of();

        List<PotentialJsonEnvelopeSpan> spans = new ArrayList<>();
        List<CodeFenceEnvelope> envelopes = codeFenceEnvelopes(value);
        int segmentStart = 0;
        for (CodeFenceEnvelope envelope : envelopes) {
            collectPotentialStandaloneJsonSpans(
                    value,
                    segmentStart,
                    envelope.startInclusive(),
                    false,
                    spans);
            segmentStart = envelope.endExclusive();
        }
        collectPotentialStandaloneJsonSpans(
                value, segmentStart, value.length(), false, spans);
        return List.copyOf(spans);
    }

    /**
     * Returns the original UTF-16 offset of an unsettled terminal JSON-protocol
     * suffix, or {@code -1} when the terminal text is settled.
     *
     * <p>This is a conservative display-only boundary for cap/abort cleanup.
     * It includes pre-name prefixes that can still become a JSON fence or a
     * standalone fallback object, but it never recognizes or authorizes an
     * executable proposal. Complete ordinary JSON is therefore retained.
     */
    public static int unsettledJsonProtocolTailStart(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) return -1;

        List<CodeFenceEnvelope> envelopes = codeFenceEnvelopes(value);
        int availableStart = 0;
        if (!envelopes.isEmpty()) {
            CodeFenceEnvelope last = envelopes.get(envelopes.size() - 1);
            if (!last.closed()) {
                return last.jsonOpening()
                        ? last.jsonOpeningStartInclusive()
                        : -1;
            }
            availableStart = last.endExclusive();
        }

        int incompleteFence = incompleteJsonFenceOpeningTailStart(
                value, availableStart);
        if (incompleteFence >= 0) {
            return incompleteFence;
        }
        int incompleteLeadingAdjacent =
                incompleteLeadingAdjacentJsonTailStart(value);
        if (incompleteLeadingAdjacent >= 0) {
            return incompleteLeadingAdjacent;
        }
        return incompleteStandaloneJsonTailStart(
                value, availableStart, value.length());
    }

    private static int incompleteLeadingAdjacentJsonTailStart(String text) {
        int start = stripStartIndex(text, 0, text.length());
        if (start >= text.length() || text.charAt(start) != '{') return -1;

        String adjacentSource = text.substring(start);
        try (JsonParser parser = MAPPER.createParser(adjacentSource)) {
            while (true) {
                com.fasterxml.jackson.core.JsonToken token = parser.nextToken();
                if (token == null) return -1;
                long relativeStart = parser.currentTokenLocation().getCharOffset();
                int valueStart = relativeStart < 0
                        || relativeStart > adjacentSource.length()
                        ? -1
                        : start + Math.toIntExact(relativeStart);
                try {
                    MAPPER.readTree(parser);
                } catch (Exception incompleteValue) {
                    return token == com.fasterxml.jackson.core.JsonToken.START_OBJECT
                            && valueStart >= 0
                            && findJsonLikeObjectEnd(text, valueStart) < 0
                            ? valueStart
                            : -1;
                }
            }
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Strip recognized Talos tool-call protocol text, returning only prose. */
    public static String stripToolCalls(String text) {
        return stripToolCalls(text, true);
    }

    /**
     * Strips tool protocol without trimming or collapsing any surrounding
     * non-protocol bytes. This is the terminal projection used by streaming
     * display code that owns its own whitespace contract.
     */
    public static String stripToolCallsPreservingLayout(String text) {
        return stripToolCalls(text, false);
    }

    private static String stripToolCalls(String text, boolean normalizeLayout) {
        if (text == null) return "";
        String candidate = text;
        for (int pass = 0; pass < MAX_STRIP_FIXED_POINT_PASSES; pass++) {
            String projected = stripToolCallsOnce(candidate, normalizeLayout);
            if (!containsStripEligibleProtocol(projected)) {
                return projected;
            }
            if (projected.equals(candidate)) {
                return failClosedBeforeProtocol(candidate, normalizeLayout);
            }
            candidate = projected;
        }

        // Each pass is deletion-only apart from bounded whitespace cleanup, so
        // an adversarial promotion chain cannot consume unbounded CPU. Preserve
        // the mechanically safe prefix and fail closed before the first range
        // that still has protocol status after the fixed pass budget.
        return failClosedBeforeProtocol(candidate, normalizeLayout);
    }

    private static String stripToolCallsOnce(
            String text,
            boolean normalizeLayout) {
        String stripped = stripRecognizedXmlBlocks(text);
        stripped = stripSelectedJsonProtocol(stripped);
        stripped = stripPotentialJsonEnvelopes(stripped);
        stripped = stripMalformedToolProtocolBlocks(stripped);
        return normalizeLayout ? normalizeStrippedToolText(stripped) : stripped;
    }

    private static boolean containsStripEligibleProtocol(String text) {
        return containsToolCalls(text)
                || !potentialExecutableJsonEnvelopeSpans(text).isEmpty()
                || !potentialExecutableStandaloneJsonSpans(text).isEmpty();
    }

    private static String failClosedBeforeProtocol(
            String text,
            boolean normalizeLayout) {
        int firstProtocol = firstStripEligibleProtocolStart(text);
        if (firstProtocol <= 0) return "";
        String prefix = text.substring(0, firstProtocol);
        return normalizeLayout ? normalizeStrippedToolText(prefix) : prefix;
    }

    private static int firstStripEligibleProtocolStart(String text) {
        int first = text.length();

        Matcher xml = STRIP_PATTERN.matcher(text);
        while (xml.find()) {
            if (containsAcceptedToolNameField(xml.group())) {
                first = Math.min(first, xml.start());
                break;
            }
        }

        List<SelectedExecutableJsonSpan> selected =
                selectExecutableJsonProposals(text).sourceOrder();
        if (!selected.isEmpty()) {
            first = Math.min(
                    first, selected.getFirst().envelopeStartInclusive());
        }
        List<PotentialJsonEnvelopeSpan> fenced =
                potentialExecutableJsonEnvelopeSpans(text);
        if (!fenced.isEmpty()) {
            first = Math.min(first, fenced.getFirst().startInclusive());
        }
        List<PotentialJsonEnvelopeSpan> standalone =
                potentialExecutableStandaloneJsonSpans(text);
        if (!standalone.isEmpty()) {
            first = Math.min(first, standalone.getFirst().startInclusive());
        }
        return first == text.length() ? -1 : first;
    }

    private static String normalizeStrippedToolText(String text) {
        String normalized = text.replaceAll("\\n{3,}", "\n\n");
        return normalized.isBlank() ? "" : normalized.strip();
    }

    /**
     * Returns true when {@code text} contains a complete Talos tool-call protocol
     * fragment. This is non-executing and is safe for core stream-control code.
     */
    public static boolean containsToolCalls(String text) {
        if (text == null || text.isBlank()) return false;
        if (containsRecognizedXmlToolCall(text)) return true;
        return !recognizedExecutableJsonSpans(text).isEmpty();
    }

    /**
     * Returns true when an XML-variant block names a recognized Talos tool or
     * accepted alias. Foreign-named XML (a model quoting some other agent's
     * protocol) is prose, not Talos protocol, matching the fence gate.
     */
    public static boolean containsRecognizedXmlToolCall(String text) {
        if (text == null || text.isBlank()) return false;
        Matcher matcher = STRIP_PATTERN.matcher(text);
        while (matcher.find()) {
            if (containsAcceptedToolNameField(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when a code fence contains a recognized Talos tool name or accepted alias. */
    public static boolean containsRecognizedCodeFenceToolCall(String text) {
        return recognizedExecutableJsonSpans(text).stream()
                .anyMatch(span -> span.kind() == JsonProtocolKind.FENCED_JSON);
    }

    /**
     * Returns true when the first JSON value in {@code text} is an object that
     * names a recognized Talos tool or accepted alias. This intentionally
     * preserves the pinned text-fallback parser's first-root behavior.
     */
    public static boolean looksLikeStandaloneToolJson(String text) {
        String value = text == null ? "" : text;
        int[] range = strippedRange(value, 0, value.length());
        if (range[0] >= range[1] || value.charAt(range[0]) != '{') {
            return false;
        }
        JsonNode root = readFirstJsonObject(value.substring(range[0], range[1]));
        return isRecognizedToolCallObject(root);
    }

    /**
     * Returns true for a narrow malformed native-tool protocol debris shape:
     * a small standalone JSON-like array containing only commas and whitespace,
     * for example {@code [ , ]}.
     */
    public static boolean looksLikeMalformedProtocolArrayDebris(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() < 3 || trimmed.length() > 512) return false;
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false;

        String inner = trimmed.substring(1, trimmed.length() - 1);
        boolean sawComma = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == ',') {
                sawComma = true;
            } else if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return sawComma;
    }

    /**
     * Returns true for a JSON-like Talos tool-call object that cannot be parsed
     * as executable JSON protocol.
     */
    public static boolean looksLikeMalformedToolProtocol(String text) {
        return !malformedToolProtocolSpans(text).isEmpty();
    }

    private static String stripMalformedToolProtocolBlocks(String text) {
        List<int[]> spans = malformedToolProtocolSpans(text);
        if (spans.isEmpty()) return text;

        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (int[] span : spans) {
            if (span[0] > cursor) {
                out.append(text, cursor, span[0]);
            }
            cursor = Math.max(cursor, span[1]);
        }
        if (cursor < text.length()) {
            out.append(text, cursor, text.length());
        }
        return out.toString();
    }

    private static String stripRecognizedXmlBlocks(String text) {
        Matcher matcher = STRIP_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            if (containsAcceptedToolNameField(matcher.group())) {
                matcher.appendReplacement(out, "");
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String stripSelectedJsonProtocol(String text) {
        JsonProposalSelection selection = selectExecutableJsonProposals(text);
        List<PotentialJsonEnvelopeSpan> spans = new ArrayList<>(
                selection.sourceOrder().size()
                        + selection.suppressionOnlyEnvelopes().size());
        for (SelectedExecutableJsonSpan selected : selection.sourceOrder()) {
            spans.add(new PotentialJsonEnvelopeSpan(
                    selected.envelopeStartInclusive(),
                    selected.envelopeEndExclusive(),
                    true));
        }
        spans.addAll(selection.suppressionOnlyEnvelopes());
        if (spans.isEmpty()) return text;
        spans.sort(Comparator
                .comparingInt(PotentialJsonEnvelopeSpan::startInclusive)
                .thenComparing(Comparator.comparingInt(
                        PotentialJsonEnvelopeSpan::endExclusive).reversed()));

        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (PotentialJsonEnvelopeSpan span : spans) {
            if (span.startInclusive() < cursor) {
                cursor = Math.max(cursor, span.endExclusive());
                continue;
            }
            if (span.startInclusive() > cursor) {
                out.append(text, cursor, span.startInclusive());
            }
            cursor = span.endExclusive();
        }
        if (cursor < text.length()) {
            out.append(text, cursor, text.length());
        }
        return out.toString();
    }

    private static String stripPotentialJsonEnvelopes(String text) {
        List<PotentialJsonEnvelopeSpan> spans =
                potentialExecutableJsonEnvelopeSpans(text);
        if (spans.isEmpty()) return text;

        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (PotentialJsonEnvelopeSpan span : spans) {
            if (span.startInclusive() < cursor
                    || span.endExclusive() > text.length()) {
                continue;
            }
            out.append(text, cursor, span.startInclusive());
            cursor = span.endExclusive();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    private static List<int[]> malformedToolProtocolSpans(String text) {
        List<PotentialJsonEnvelopeSpan> potential =
                potentialExecutableStandaloneJsonSpans(text);
        if (potential.isEmpty()) return List.of();
        List<int[]> spans = new ArrayList<>();
        for (PotentialJsonEnvelopeSpan span : potential) {
            spans.add(new int[] { span.startInclusive(), span.endExclusive() });
        }
        return List.copyOf(spans);
    }

    private static boolean containsAcceptedToolNameField(String candidate) {
        Matcher nameMatcher = TOOL_NAME_FIELD_PATTERN.matcher(candidate);
        while (nameMatcher.find()) {
            if (isRecognizedToolName(nameMatcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Conservatively detects a complete, decodable JSON name-field value that
     * resolves to an accepted or custom {@code talos.*} tool identity.
     *
     * <p>This predicate is for buffering and display suppression of incomplete
     * or malformed protocol only. It does not validate a JSON proposal and
     * must never be used to authorize, approve, or execute a tool call.
     */
    public static boolean containsPotentialExecutableToolNameField(String text) {
        String value = text == null ? "" : text;
        Matcher matcher = JSON_TOOL_NAME_STRING_START.matcher(value);
        while (matcher.find()) {
            int quoteStart = matcher.start(1);
            int quoteEnd = jsonStringEnd(value, quoteStart);
            if (quoteEnd < 0) {
                continue;
            }
            try {
                JsonNode decoded = MAPPER.readTree(
                        value.substring(quoteStart, quoteEnd));
                if (decoded != null
                        && decoded.isTextual()
                        && isRecognizedToolName(decoded.textValue())) {
                    return true;
                }
            } catch (Exception ignored) {
                // A malformed string is not mechanically attributable to a
                // tool identity. The caller may keep buffering on other
                // lexical evidence but cannot treat this as executable.
            }
        }
        return false;
    }

    /**
     * Conservatively detects an unterminated JSON name-field string whose
     * decoded prefix could still become an accepted/custom-Talos identity.
     *
     * <p>This exists only for terminal buffering and display suppression.
     * It does not make incomplete JSON parseable and must never be used for
     * tool authorization, approval, or execution.
     */
    public static boolean containsPotentialExecutableToolNamePrefix(String text) {
        String value = text == null ? "" : text;
        Matcher matcher = JSON_TOOL_NAME_STRING_START.matcher(value);
        while (matcher.find()) {
            int quoteStart = matcher.start(1);
            if (jsonStringEnd(value, quoteStart) >= 0) {
                continue;
            }
            String decodedPrefix =
                    decodeJsonStringPrefix(value, quoteStart + 1);
            if (ToolNamePolicy.isPotentialAcceptedToolNamePrefix(decodedPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<CodeFenceEnvelope> codeFenceEnvelopes(String text) {
        List<CodeFenceEnvelope> envelopes = new ArrayList<>();
        int lastLineBreakIndex = lastLineBreakIndex(text);
        int searchFrom = 0;
        while (searchFrom < text.length()) {
            FenceOpening opening = nextFenceOpening(text, searchFrom);
            if (opening == null) {
                break;
            }

            int close = structuralFenceClose(
                    text,
                    opening.payloadStartInclusive(),
                    opening.jsonOpeningStartInclusive() >= 0,
                    lastLineBreakIndex);
            if (opening.jsonOpeningStartInclusive() < 0) {
                int nestedExecutableOpening = nextExecutableFenceOpeningStart(
                        text, opening.payloadStartInclusive());
                if (nestedExecutableOpening >= 0
                        && (close < 0 || nestedExecutableOpening < close)) {
                    envelopes.add(new CodeFenceEnvelope(
                            opening.startInclusive(),
                            opening.jsonOpeningStartInclusive(),
                            opening.payloadStartInclusive(),
                            -1,
                            nestedExecutableOpening,
                            false));
                    searchFrom = nestedExecutableOpening;
                    continue;
                }
            }
            if (close < 0) {
                envelopes.add(new CodeFenceEnvelope(
                        opening.startInclusive(),
                        opening.jsonOpeningStartInclusive(),
                        opening.payloadStartInclusive(),
                        -1,
                        text.length(),
                        false));
                break;
            }

            int closeEnd = structuralClosingFenceEnd(text, close);

            envelopes.add(new CodeFenceEnvelope(
                    opening.startInclusive(),
                    opening.jsonOpeningStartInclusive(),
                    opening.payloadStartInclusive(),
                    close,
                    closeEnd,
                    true));
            searchFrom = closeEnd;
        }
        return List.copyOf(envelopes);
    }

    /**
     * Scans one opening fence without regex backtracking. A maximal opening
     * marker run preserves the predecessor's four-backtick behavior: the JSON
     * candidate begins at the final three markers in that run.
     */
    private static FenceOpening nextFenceOpening(String text, int fromIndex) {
        int cursor = Math.max(0, fromIndex);
        while (cursor + 2 < text.length()) {
            if (text.charAt(cursor) != '`') {
                cursor++;
                continue;
            }

            int runStart = cursor;
            while (cursor < text.length() && text.charAt(cursor) == '`') {
                cursor++;
            }
            int runLength = cursor - runStart;
            if (runLength < 3) {
                continue;
            }

            int lineBreakStart = firstLineBreakStart(text, cursor);
            if (lineBreakStart < 0) {
                return null;
            }
            int lineBreakEnd = lineBreakEnd(text, lineBreakStart);
            int jsonOpeningStart = jsonOpeningInfoMatches(
                    text, cursor, lineBreakStart)
                    ? cursor - 3
                    : -1;
            return new FenceOpening(
                    runStart,
                    jsonOpeningStart,
                    lineBreakEnd);
        }
        return null;
    }

    private static int firstLineBreakStart(String text, int fromIndex) {
        for (int i = fromIndex; i < text.length(); i++) {
            if (isLineBreakCodeUnit(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lineBreakEnd(String text, int lineBreakStart) {
        if (text.charAt(lineBreakStart) == '\r'
                && lineBreakStart + 1 < text.length()
                && text.charAt(lineBreakStart + 1) == '\n') {
            return lineBreakStart + 2;
        }
        return lineBreakStart + 1;
    }

    private static int optionalLineBreakStartBefore(String text, int endExclusive) {
        if (endExclusive <= 0) return endExclusive;
        char previous = text.charAt(endExclusive - 1);
        if (previous == '\n'
                && endExclusive >= 2
                && text.charAt(endExclusive - 2) == '\r') {
            return endExclusive - 2;
        }
        return isLineBreakCodeUnit(previous)
                ? endExclusive - 1
                : endExclusive;
    }

    private static boolean isLineBreakCodeUnit(char value) {
        return value == '\r'
                || value == '\n'
                || value == '\u000B'
                || value == '\f'
                || value == '\u0085'
                || value == '\u2028'
                || value == '\u2029';
    }

    private static boolean jsonOpeningInfoMatches(
            String text,
            int infoStartInclusive,
            int infoEndExclusive) {
        int cursor = infoStartInclusive;
        if (cursor + 4 <= infoEndExclusive
                && text.regionMatches(cursor, "json", 0, 4)) {
            cursor += 4;
        }
        while (cursor < infoEndExclusive) {
            char value = text.charAt(cursor++);
            if (value != ' ' && value != '\t') {
                return false;
            }
        }
        return true;
    }

    /**
     * Scans one envelope forward once and returns the first maximal backtick
     * run that is either an unconditional structural close or occurs after a
     * complete first JSON root. This intentionally preserves the fail-closed
     * raw-marker contract; it is not a Markdown string parser.
     */
    private static int structuralFenceClose(
            String text,
            int payloadStartInclusive,
            boolean executableOpening,
            int lastLineBreakIndex) {
        int firstRootStart = executableOpening
                ? stripStartIndex(
                        text, payloadStartInclusive, text.length())
                : -1;
        boolean trackingRoot = firstRootStart >= payloadStartInclusive
                && firstRootStart < text.length()
                && text.charAt(firstRootStart) == '{';
        int rootDepth = 0;
        boolean rootInString = false;
        boolean rootEscaped = false;
        int firstRootEndInclusive = -1;

        int cursor = payloadStartInclusive;
        int scanCursor = payloadStartInclusive;
        boolean linePrefixWhitespace = true;
        while (cursor + 2 < text.length()) {
            int runStart = text.indexOf("```", cursor);
            if (runStart < 0) return -1;
            int runEnd = runStart + 3;
            while (runEnd < text.length() && text.charAt(runEnd) == '`') {
                runEnd++;
            }

            for (int index = scanCursor; index < runStart; index++) {
                char value = text.charAt(index);
                if (isLineBreakCodeUnit(value)) {
                    linePrefixWhitespace = true;
                } else if (value != ' ' && value != '\t') {
                    linePrefixWhitespace = false;
                }

                if (!trackingRoot
                        || firstRootEndInclusive >= 0
                        || index < firstRootStart) {
                    continue;
                }
                if (rootInString) {
                    if (rootEscaped) {
                        rootEscaped = false;
                    } else if (value == '\\') {
                        rootEscaped = true;
                    } else if (value == '"') {
                        rootInString = false;
                    }
                } else if (value == '"') {
                    rootInString = true;
                } else if (value == '{') {
                    rootDepth++;
                } else if (value == '}') {
                    rootDepth--;
                    if (rootDepth == 0) {
                        firstRootEndInclusive = index;
                    }
                }
            }

            boolean completeRootBeforeRun = trackingRoot
                    && firstRootEndInclusive >= 0
                    && firstRootEndInclusive < runStart;
            boolean sharedAdjacentOpening = runEnd - runStart >= 6
                    && lastLineBreakIndex >= runEnd;
            if ((linePrefixWhitespace
                    && isFenceClosingLineSuffix(text, runEnd))
                    || completeRootBeforeRun
                    || sharedAdjacentOpening) {
                return runStart;
            }

            // A backtick cannot change object depth or string membership.
            // When it follows a JSON escape inside a string, however, the
            // first code unit consumes that escape before scanning resumes.
            if (trackingRoot
                    && firstRootEndInclusive < 0
                    && rootInString
                    && rootEscaped) {
                rootEscaped = false;
            }
            cursor = runEnd;
            scanCursor = runEnd;
            linePrefixWhitespace = false;
        }
        return -1;
    }

    private static int lastLineBreakIndex(String text) {
        for (int index = text.length() - 1; index >= 0; index--) {
            if (isLineBreakCodeUnit(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isFenceClosingLineSuffix(String text, int runEnd) {
        for (int index = runEnd;
                index < text.length()
                        && !isLineBreakCodeUnit(text.charAt(index));
                index++) {
            char value = text.charAt(index);
            if (value != ' ' && value != '\t') return false;
        }
        return true;
    }

    private static int nextExecutableFenceOpeningStart(
            String text,
            int startInclusive) {
        int cursor = startInclusive;
        while (cursor < text.length()) {
            FenceOpening opening = nextFenceOpening(text, cursor);
            if (opening == null) return -1;
            if (opening.jsonOpeningStartInclusive() >= 0) {
                return opening.startInclusive();
            }
            cursor = Math.max(
                    opening.payloadStartInclusive(), opening.startInclusive() + 1);
        }
        return -1;
    }

    private static int structuralClosingFenceEnd(String text, int closeStart) {
        int runEnd = closeStart + 3;
        while (runEnd < text.length() && text.charAt(runEnd) == '`') {
            runEnd++;
        }

        int adjacentOpeningStart = closeStart + 3;
        if (runEnd - adjacentOpeningStart < 3) {
            return runEnd;
        }
        FenceOpening adjacent = nextFenceOpening(text, adjacentOpeningStart);
        return adjacent != null && adjacent.startInclusive() == adjacentOpeningStart
                ? adjacentOpeningStart
                : runEnd;
    }

    private static int firstRawToolNameKeyEnd(
            String text,
            int startInclusive,
            int endExclusive) {
        for (int cursor = startInclusive; cursor < endExclusive; cursor++) {
            if (text.charAt(cursor) != '"') {
                continue;
            }
            for (String key : TOOL_NAME_KEYS) {
                if (cursor + key.length() <= endExclusive
                        && text.regionMatches(cursor, key, 0, key.length())) {
                    return cursor + key.length();
                }
            }
        }
        return -1;
    }

    private static int incompleteJsonFenceOpeningTailStart(
            String text,
            int startInclusive) {
        int runStart = -1;
        int runEnd = -1;
        for (int i = startInclusive; i < text.length(); i++) {
            if (text.charAt(i) != '`') {
                continue;
            }
            int candidateStart = i;
            while (i < text.length() && text.charAt(i) == '`') {
                i++;
            }
            runStart = candidateStart;
            runEnd = i;
            i--;
        }
        if (runStart < 0) return -1;

        int runLength = runEnd - runStart;
        String suffix = text.substring(runEnd);
        if (suffix.chars().anyMatch(codeUnit -> isLineBreakCodeUnit((char) codeUnit))) {
            return -1;
        }
        if (runLength < 3) {
            return suffix.isEmpty() ? runStart : -1;
        }
        return isPotentialJsonOpeningInfoPrefix(suffix) ? runStart : -1;
    }

    private static boolean isPotentialJsonOpeningInfoPrefix(String suffix) {
        if (suffix.isEmpty()) return true;
        boolean allSpacing = true;
        for (int i = 0; i < suffix.length(); i++) {
            char value = suffix.charAt(i);
            if (value != ' ' && value != '\t') {
                allSpacing = false;
                break;
            }
        }
        if (allSpacing) return true;

        int shared = Math.min(4, suffix.length());
        if (!"json".regionMatches(0, suffix, 0, shared)) {
            return false;
        }
        if (suffix.length() <= 4) {
            return true;
        }
        for (int i = 4; i < suffix.length(); i++) {
            char value = suffix.charAt(i);
            if (value != ' ' && value != '\t') {
                return false;
            }
        }
        return true;
    }

    private static int incompleteStandaloneJsonTailStart(
            String text,
            int startInclusive,
            int endExclusive) {
        int objectStart = -1;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = startInclusive; i < endExclusive; i++) {
            char value = text.charAt(i);
            if (objectStart < 0) {
                if (value == '{' && isStandaloneFallbackBoundary(text, i)) {
                    objectStart = i;
                    depth = 1;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '"') {
                quote = value;
            } else if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    objectStart = -1;
                }
            }
        }
        return objectStart;
    }

    private static boolean isStandaloneFallbackBoundary(String text, int objectStart) {
        int lineStart = text.lastIndexOf('\n', objectStart - 1) + 1;
        for (int i = lineStart; i < objectStart; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void collectStandaloneJsonSpans(
            String text,
            List<CodeFenceEnvelope> codeFenceEnvelopes,
            List<JsonCandidateSpan> spans,
            List<JsonValueSpan> leadingAdjacentValues) {
        Matcher bare = BARE_JSON_PATTERN.matcher(text);
        int envelopeIndex = 0;
        while (bare.find()) {
            int start = bare.start(1);
            while (envelopeIndex < codeFenceEnvelopes.size()
                    && codeFenceEnvelopes.get(envelopeIndex).endExclusive() <= start) {
                envelopeIndex++;
            }
            if (isInsideCurrentEnvelope(start, codeFenceEnvelopes, envelopeIndex)) {
                continue;
            }
            int rootEnd = findJsonLikeObjectEnd(text, start, bare.end(1));
            int rootEndExclusive = rootEnd < 0 ? -1 : rootEnd + 1;
            JsonNode root = rootEndExclusive > start
                    ? readFirstJsonObject(text.substring(start, rootEndExclusive))
                    : null;
            if (isRecognizedToolCallObject(root)) {
                spans.add(new JsonCandidateSpan(
                        start,
                        rootEndExclusive,
                        start,
                        rootEndExclusive,
                        rootEndExclusive,
                        JsonProtocolKind.STANDALONE_JSON));
            }
        }

        int[] stripped = strippedRange(text, 0, text.length());
        if (stripped[0] >= stripped[1] || text.charAt(stripped[0]) != '{') {
            return;
        }

        if (isInsideEnvelope(stripped[0], codeFenceEnvelopes)) {
            return;
        }

        String adjacentSource = text.substring(stripped[0], stripped[1]);
        try (JsonParser parser = MAPPER.createParser(adjacentSource)) {
            while (parser.nextToken() != null) {
                long relativeStart = parser.currentTokenLocation().getCharOffset();
                JsonNode root = MAPPER.readTree(parser);
                if (root == null
                        || relativeStart < 0
                        || relativeStart > adjacentSource.length()) {
                    continue;
                }

                int start = stripped[0] + Math.toIntExact(relativeStart);
                if (isInsideEnvelope(start, codeFenceEnvelopes)) {
                    return;
                }
                long relativeEnd = parser.currentLocation().getCharOffset();
                if (relativeEnd < relativeStart
                        || relativeEnd > adjacentSource.length()) {
                    return;
                }
                int endExclusive = stripped[0] + Math.toIntExact(relativeEnd);
                if (root.isObject()) {
                    int structuralEnd = findJsonLikeObjectEnd(text, start);
                    if (structuralEnd < 0) return;
                    endExclusive = structuralEnd + 1;
                }
                leadingAdjacentValues.add(new JsonValueSpan(start, endExclusive));
                if (isRecognizedToolCallObject(root)) {
                    spans.add(new JsonCandidateSpan(
                            start,
                            endExclusive,
                            start,
                            endExclusive,
                            endExclusive,
                            JsonProtocolKind.STANDALONE_JSON));
                }
            }
        } catch (Exception ignored) {
            // Match the predecessor adjacent-value pass: retain complete
            // values observed before the first malformed/non-JSON boundary.
        }
    }

    private static boolean isInsideEnvelope(
            int offset,
            List<CodeFenceEnvelope> envelopes) {
        int low = 0;
        int high = envelopes.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            CodeFenceEnvelope envelope = envelopes.get(middle);
            if (offset < envelope.startInclusive()) {
                high = middle - 1;
            } else if (offset >= envelope.endExclusive()) {
                low = middle + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideCurrentEnvelope(
            int offset,
            List<CodeFenceEnvelope> envelopes,
            int envelopeIndex) {
        return envelopeIndex < envelopes.size()
                && offset >= envelopes.get(envelopeIndex).startInclusive()
                && offset < envelopes.get(envelopeIndex).endExclusive();
    }

    private static int[] strippedRange(String text, int startInclusive, int endExclusive) {
        int start = stripStartIndex(text, startInclusive, endExclusive);
        int end = endExclusive;
        while (end > start) {
            int codePoint = text.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return new int[] { start, end };
    }

    private static int leadingJavaWhitespaceEnd(String text) {
        return stripStartIndex(text, 0, text.length());
    }

    private static int trailingJsonWhitespaceEnd(String text, int startInclusive) {
        int cursor = startInclusive;
        while (cursor < text.length()) {
            char value = text.charAt(cursor);
            if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private static int stripStartIndex(String text, int startInclusive, int endExclusive) {
        int start = startInclusive;
        while (start < endExclusive) {
            int codePoint = text.codePointAt(start);
            if (!Character.isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        return start;
    }

    private static JsonNode readFirstJsonObject(String payload) {
        if (payload == null || payload.isEmpty() || payload.charAt(0) != '{') {
            return null;
        }
        try (JsonParser parser = MAPPER.createParser(payload)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) {
                return null;
            }
            return root;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isRecognizedToolCallObject(JsonNode root) {
        JsonNode toolObject = selectToolObject(root);
        String name = firstToolName(toolObject);
        return name != null && isRecognizedToolName(name);
    }

    private static JsonNode selectToolObject(JsonNode root) {
        if (root == null || !root.isObject()) return null;

        // Preserve the pinned parser contract: the first recognized wrapper
        // in this exact order owns the call, even when a later wrapper or the
        // outer object also carries a name-like field.
        for (String wrapper : List.of("tool_call", "function_call")) {
            JsonNode inner = root.path(wrapper);
            if (!inner.isMissingNode() && inner.isObject() && hasNameAlias(inner)) {
                return inner;
            }
        }
        return root;
    }

    private static String firstToolName(JsonNode root) {
        if (root == null || !root.isObject()) return null;

        // Preserve the pinned parser's first-name-key precedence.
        for (String key : List.of("name", "function", "function_name", "tool_name", "tool")) {
            JsonNode node = root.path(key);
            if (node.isMissingNode()) continue;
            String rawName = node.asText("").strip();
            if (!rawName.isBlank()) return rawName;
        }
        return null;
    }

    private static void collectPotentialStandaloneJsonSpans(
            String text,
            int startInclusive,
            int endExclusive,
            boolean includeRecognized,
            List<PotentialJsonEnvelopeSpan> spans) {
        int objectStart = -1;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = startInclusive; i < endExclusive; i++) {
            char value = text.charAt(i);
            if (objectStart < 0) {
                if (value == '{') {
                    objectStart = i;
                    depth = 1;
                    quote = 0;
                    escaped = false;
                }
                continue;
            }

            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '"') {
                quote = value;
            } else if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    addPotentialStandaloneJsonSpan(
                            text,
                            objectStart,
                            i + 1,
                            true,
                            includeRecognized,
                            spans);
                    objectStart = -1;
                }
            }
        }

        if (objectStart >= 0) {
            addPotentialStandaloneJsonSpan(
                    text,
                    objectStart,
                    endExclusive,
                    false,
                    includeRecognized,
                    spans);
        }
    }

    private static void addPotentialStandaloneJsonSpan(
            String text,
            int startInclusive,
            int endExclusive,
            boolean closed,
            boolean includeRecognized,
            List<PotentialJsonEnvelopeSpan> spans) {
        String candidate = text.substring(startInclusive, endExclusive);
        if (!hasPotentialExecutableToolIdentity(candidate)) {
            return;
        }
        if (closed
                && !includeRecognized
                && looksLikeStandaloneToolJson(candidate)) {
            return;
        }
        spans.add(new PotentialJsonEnvelopeSpan(
                startInclusive, endExclusive, closed));
    }

    private static boolean hasPotentialExecutableToolIdentity(String candidate) {
        return containsPotentialExecutableToolNameField(candidate)
                || containsPotentialExecutableToolNamePrefix(candidate)
                || containsAcceptedToolNameField(candidate);
    }

    private record FenceOpening(
            int startInclusive,
            int jsonOpeningStartInclusive,
            int payloadStartInclusive
    ) {}

    private record JsonCandidateSpan(
            int rootStartInclusive,
            int rootEndExclusive,
            int envelopeStartInclusive,
            int envelopeContentEndExclusive,
            int envelopeEndExclusive,
            JsonProtocolKind kind
    ) {
        SelectedExecutableJsonSpan toSelected() {
            return new SelectedExecutableJsonSpan(
                    rootStartInclusive,
                    rootEndExclusive,
                    envelopeStartInclusive,
                    envelopeContentEndExclusive,
                    envelopeEndExclusive,
                    kind);
        }
    }

    private record JsonValueSpan(int startInclusive, int endExclusive) {}

    private record CodeFenceEnvelope(
            int startInclusive,
            int jsonOpeningStartInclusive,
            int payloadStartInclusive,
            int closeStartInclusive,
            int endExclusive,
            boolean closed
    ) {
        boolean jsonOpening() {
            return jsonOpeningStartInclusive >= 0;
        }
    }

    private static int findJsonLikeObjectEnd(String text, int start) {
        return findJsonLikeObjectEnd(text, start, text.length());
    }

    private static int findJsonLikeObjectEnd(
            String text,
            int start,
            int limitExclusive) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;

        int limit = Math.min(text.length(), Math.max(start, limitExclusive));
        for (int i = start; i < limit; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
                if (depth < 0) return -1;
            }
        }
        return -1;
    }

    private static int jsonStringEnd(String text, int quoteStart) {
        boolean escaped = false;
        for (int i = quoteStart + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i + 1;
            }
        }
        return -1;
    }

    private static String decodeJsonStringPrefix(String text, int valueStart) {
        StringBuilder decoded = new StringBuilder(text.length() - valueStart);
        for (int i = valueStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                decoded.append(c);
                continue;
            }
            if (++i >= text.length()) {
                break;
            }
            char escaped = text.charAt(i);
            switch (escaped) {
                case '"' -> decoded.append('"');
                case '\\' -> decoded.append('\\');
                case '/' -> decoded.append('/');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> {
                    if (i + 4 >= text.length()) {
                        return decoded.toString();
                    }
                    int codeUnit = 0;
                    for (int hexIndex = i + 1; hexIndex <= i + 4; hexIndex++) {
                        int digit = Character.digit(text.charAt(hexIndex), 16);
                        if (digit < 0) {
                            return decoded.toString();
                        }
                        codeUnit = (codeUnit << 4) | digit;
                    }
                    decoded.append((char) codeUnit);
                    i += 4;
                }
                default -> decoded.append(escaped);
            }
        }
        return decoded.toString();
    }

    private static boolean hasNameAlias(JsonNode root) {
        for (String key : List.of("name", "function", "function_name", "tool_name", "tool")) {
            if (root.has(key)) return true;
        }
        return false;
    }

    private static boolean isRecognizedToolName(String rawName) {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve(rawName);
        return decision.accepted()
                || (decision.status() == ToolNamePolicy.AliasDecisionStatus.UNKNOWN
                && decision.canonicalToolName().startsWith("talos."));
    }
}
