package dev.talos.core.util;

import dev.talos.core.tool.ToolProtocolText;

import java.util.regex.Pattern;

/**
 * Utilities for sanitizing untrusted text before sending to or printing from the LLM.
 */
public final class Sanitize {
    private Sanitize() {}

    private static final int MAX_PROJECTION_STABILIZATION_PASSES = 8;

    // ANSI escape sequences
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");
    // Control chars & nulls (TAB and LF/CR are kept for readability)
    private static final Pattern CTRL = Pattern.compile("[\u0000-\u0008\u000B-\u001F\u007F]");
    // Hidden chain-of-thought blocks (e.g., <think>...</think>)
    private static final Pattern THINK = Pattern.compile("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>");
    // Same source language after stripThinkTags decodes its supported angle-bracket escapes.
    // Opening and closing tags are classified independently so literal tag
    // fragments cannot pair across immutable executable JSON boundaries.
    private static final Pattern SOURCE_THINK_OPEN = Pattern.compile(
            "(?is)(?:<|(?-i:\\\\u003c))\\s*think\\s*(?:>|(?-i:\\\\u003e))"
    );
    private static final Pattern SOURCE_THINK_CLOSE = Pattern.compile(
            "(?is)(?:<|(?-i:\\\\u003c))\\s*/\\s*think\\s*(?:>|(?-i:\\\\u003e))"
    );
    private static final java.util.Set<String> SUSPICIOUS_PAIRED_TAGS =
            java.util.Set.of(
                    "script", "style", "iframe", "object", "embed", "meta",
                    "link", "svg", "form", "input", "textarea", "button");

    /** Matches &lt;tool_call&gt;...&lt;/tool_call&gt; blocks (and common tag variants).
     *  DEPRECATED COMPATIBILITY ONLY - retained for models that emit XML from training habits.
     *  JSON code-fenced tool calls are the actively instructed text fallback format.
     *  Scheduled for removal once native tool calling is stable across model versions. */
    private static final java.util.List<String> XML_PROTECTED_OPENERS =
            java.util.List.of("<tool_call>", "<function_call>");
    private static final java.util.List<String> XML_PROTECTED_CLOSERS =
            java.util.List.of("</tool_call>", "</function_call>");
    private static final java.util.List<String> XML_PROTOCOL_OPENERS =
            java.util.List.of(
                    "<tool_call>", "<function_call>", "<tool>", "<function>");
    private static final java.util.List<String> XML_PROTOCOL_CLOSERS =
            java.util.List.of(
                    "</tool_call>", "</function_call>", "</tool>", "</function>");

    /**
     * Strips ANSI escape sequences, control characters, and nulls from the input string.
     */
    public static String stripControl(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = ANSI.matcher(s).replaceAll("");
        out = CTRL.matcher(out).replaceAll("");
        return out;
    }

    /**
     * Removes suspicious HTML and script-like content from the input string.
     */
    public static String stripSuspiciousHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return stripSuspiciousHtmlLinear(s);
    }

    /**
     * Removes &lt;think&gt;...&lt;/think&gt; blocks entirely from the input string.
     */
    public static String dropThinkBlocks(String s) {
        if (s == null || s.isEmpty()) return "";
        return THINK.matcher(s).replaceAll("");
    }

    /**
     * Sanitizes a string before including it in a prompt to the model.
     * Applies control character and suspicious HTML stripping.
     */
    public static String sanitizeForPrompt(String s) {
        return stripSuspiciousHtml(stripControl(s));
    }

    /**
     * Sanitizes a string before printing to terminal.
     * Applies control character, suspicious HTML, and think block stripping.
     */
    public static String sanitizeForOutput(String s) {
        return stripSuspiciousHtml(stripControl(dropThinkBlocks(s)));
    }

    /**
     * Converts common UI punctuation and symbols to ASCII fallbacks for
     * dumb terminals and redirected transcript capture.
     *
     * <p>This is deliberately not part of prompt sanitization. Model-facing
     * prompts may keep their original punctuation; only terminal output should
     * be downgraded when capabilities say Unicode is unsafe.
     */
    public static String toAsciiFallback(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == '\n' || cp == '\r' || cp == '\t' || (cp >= 0x20 && cp <= 0x7E)) {
                out.appendCodePoint(cp);
                continue;
            }

            switch (cp) {
                case 0x00A0, 0x202F -> out.append(' '); // non-breaking / narrow non-breaking space
                case 0x2018, 0x2019, 0x201B, 0x2032 -> out.append('\'');
                case 0x201C, 0x201D, 0x201F, 0x2033 -> out.append('"');
                case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212 -> out.append('-');
                case 0x2026 -> out.append("...");
                case 0x2022, 0x25E6, 0x2043 -> out.append('*');
                case 0x2190 -> out.append("<-");
                case 0x2192, 0x21D2 -> out.append("->");
                case 0x2194 -> out.append("<->");
                case 0x2264 -> out.append("<=");
                case 0x2265 -> out.append(">=");
                case 0x2713, 0x2714, 0x2705 -> out.append("[ok]");
                case 0x2717, 0x2718, 0x274C -> out.append("[error]");
                case 0x26A0 -> out.append("[warning]");
                case 0x2500, 0x2501, 0x2550 -> out.append('-');
                case 0x2502, 0x2503, 0x2551 -> out.append('|');
                case 0x250C, 0x2510, 0x2514, 0x2518,
                     0x251C, 0x2524, 0x252C, 0x2534, 0x253C,
                     0x2554, 0x2557, 0x255A, 0x255D -> out.append('+');
                default -> out.append('?');
            }
        }
        return out.toString();
    }

    /**
     * Sanitizes terminal output and applies ASCII downgrade when Unicode is
     * unsafe for the active terminal/capture path.
     */
    public static String sanitizeForTerminalOutput(String s, boolean unicodeSafe) {
        String cleaned = sanitizeForOutput(s);
        return unicodeSafe ? cleaned : toAsciiFallback(cleaned);
    }

    /**
     * Sanitizes streamed LLM output while preserving {@code <tool_call>} blocks intact.
     *
     * <p>Tool-call blocks contain JSON with raw file content (HTML, CSS, JS) as parameter
     * values. Suspicious-HTML stripping would remove tags like {@code <script>} or
     * {@code <style>} from these JSON values, corrupting the tool parameters.
     *
     * <p>This method applies full sanitization (control chars, think blocks, suspicious HTML)
     * to prose text <em>outside</em> tool_call blocks. Only the canonical
     * selection's exact JSON roots are copied byte-for-byte; unselected or
     * malformed protocol is suppressed. XML compatibility blocks retain their
     * historical behavior.
     *
     * <p>Use this instead of {@link #sanitizeForOutput} in streaming assembly where the
     * response may contain tool_call blocks with HTML-valued parameters.
     */
    public static String sanitizeForOutputPreservingToolCalls(String s) {
        if (s == null || s.isEmpty()) return "";
        ToolProtocolText.JsonProposalSelection rawSelection =
                ToolProtocolText.selectExecutableJsonProposals(s);
        java.util.List<int[]> externalThinkBlocks =
                externalThinkBlocks(s, rawSelection.recognizedCandidates());
        ToolProtocolText.JsonProposalSelection selection =
                externalThinkBlocks.isEmpty()
                        ? rawSelection
                        : ToolProtocolText.selectExecutableJsonProposalsOutside(
                                s,
                                externalThinkBlocks.stream()
                                        .map(block -> new ToolProtocolText.SourceRange(
                                                block[0], block[1]))
                                        .toList());
        java.util.List<ToolProtocolText.SelectedExecutableJsonSpan> protectedJson =
                selection.sourceOrder();
        java.util.List<ToolProtocolText.SelectedExecutableJsonSpan> protectedExecution =
                selection.executionOrder();
        java.util.List<ToolProtocolText.PotentialJsonEnvelopeSpan> rejectedJson =
                selection.suppressionOnlyEnvelopes();
        java.util.List<ToolProtocolText.SourceRange> sourceSuppressionRanges =
                new java.util.ArrayList<>(
                        externalThinkBlocks.size() + rejectedJson.size());
        for (int[] block : externalThinkBlocks) {
            sourceSuppressionRanges.add(new ToolProtocolText.SourceRange(
                    block[0], block[1]));
        }
        for (ToolProtocolText.PotentialJsonEnvelopeSpan span : rejectedJson) {
            if (span.endExclusive() > span.startInclusive()) {
                sourceSuppressionRanges.add(new ToolProtocolText.SourceRange(
                        span.startInclusive(), span.endExclusive()));
            }
        }
        if (protectedJson.isEmpty()
                && rejectedJson.isEmpty()
                && externalThinkBlocks.isEmpty()) {
            java.util.List<String> expectedXml = expectedRecognizedXmlBlocks(
                    s, sourceSuppressionRanges, java.util.List.of());
            String projected = sanitizeLegacyOutputSegment(s);
            String monotonic = enforceExecutableJsonProjectionMonotonicity(
                    s,
                    java.util.List.of(),
                    java.util.List.of(),
                    projected,
                    java.util.List.of(),
                    expectedXml);
            return stabilizeSanitizedProjection(
                    s,
                    java.util.List.of(),
                    java.util.List.of(),
                    monotonic,
                    java.util.List.of(),
                    expectedXml);
        }

        java.util.List<JsonOutputRegion> copyRegions =
                new java.util.ArrayList<>(protectedJson.size() * 4);
        for (int spanIndex = 0; spanIndex < protectedJson.size(); spanIndex++) {
            ToolProtocolText.SelectedExecutableJsonSpan span =
                    protectedJson.get(spanIndex);
            if (span.kind() == ToolProtocolText.JsonProtocolKind.FENCED_JSON
                    && span.envelopeStartInclusive() < span.rootStartInclusive()) {
                copyRegions.add(new JsonOutputRegion(
                        span.envelopeStartInclusive(),
                        span.rootStartInclusive(),
                        JsonOutputRegionKind.COPY_FRAME,
                        spanIndex));
            }
            copyRegions.add(new JsonOutputRegion(
                    span.rootStartInclusive(),
                    span.rootEndExclusive(),
                    JsonOutputRegionKind.COPY_ROOT,
                    spanIndex));
            if (span.kind() == ToolProtocolText.JsonProtocolKind.FENCED_JSON
                    && span.envelopeContentEndExclusive()
                    < span.envelopeEndExclusive()) {
                int closingFrameStart = span.envelopeContentEndExclusive();
                if (span.rootEndExclusive() < closingFrameStart
                        && s.substring(
                                span.rootEndExclusive(), closingFrameStart)
                                .isBlank()) {
                    closingFrameStart = span.rootEndExclusive();
                }
                copyRegions.add(new JsonOutputRegion(
                        closingFrameStart,
                        span.envelopeEndExclusive(),
                        JsonOutputRegionKind.COPY_FRAME,
                        spanIndex));
            }
        }

        java.util.List<JsonOutputRegion> dropped =
                new java.util.ArrayList<>(
                         rejectedJson.size()
                                 + externalThinkBlocks.size());
        for (int[] block : externalThinkBlocks) {
            dropped.add(new JsonOutputRegion(
                    block[0], block[1], JsonOutputRegionKind.DROP));
        }
        for (ToolProtocolText.PotentialJsonEnvelopeSpan span : rejectedJson) {
            dropped.add(new JsonOutputRegion(
                    span.startInclusive(),
                    span.endExclusive(),
                    JsonOutputRegionKind.DROP));
        }
        addCrossRegionSuspiciousDrops(s, copyRegions, dropped);

        java.util.List<JsonOutputRegion> regions =
                new java.util.ArrayList<>(copyRegions.size() + dropped.size());
        regions.addAll(copyRegions);
        regions.addAll(mergeDroppedRegions(dropped));
        regions.sort(java.util.Comparator
                .comparingInt(JsonOutputRegion::startInclusive)
                .thenComparing(java.util.Comparator.comparingInt(
                        JsonOutputRegion::endExclusive).reversed()));
        regions = effectiveProjectionRegions(s.length(), regions);
        java.util.List<String> expectedXml = expectedRecognizedXmlBlocks(
                s, sourceSuppressionRanges, regions);

        StringBuilder result = new StringBuilder(s.length());
        java.util.List<CopiedJsonRegion> copiedJson =
                new java.util.ArrayList<>(protectedJson.size());
        int[] safePrefixStarts = new int[protectedJson.size()];
        java.util.Arrays.fill(safePrefixStarts, -1);
        int cursor = 0;
        for (JsonOutputRegion region : regions) {
            if (region.startInclusive() < cursor || region.endExclusive() > s.length()) {
                continue;
            }
            result.append(sanitizeLegacyOutputSegment(
                    s.substring(cursor, region.startInclusive())));
            if (region.kind() != JsonOutputRegionKind.DROP) {
                int outputStart = result.length();
                if (region.kind() == JsonOutputRegionKind.COPY_FRAME
                        && region.ownerIndex() >= 0
                        && safePrefixStarts[region.ownerIndex()] < 0) {
                    safePrefixStarts[region.ownerIndex()] = outputStart;
                }
                appendProjectedRegion(
                        result,
                        s,
                        region.startInclusive(),
                        region.endExclusive(),
                        region.kind());
                if (region.kind() == JsonOutputRegionKind.COPY_ROOT) {
                    int safePrefixStart = safePrefixStarts[region.ownerIndex()];
                    if (safePrefixStart < 0) safePrefixStart = outputStart;
                    copiedJson.add(new CopiedJsonRegion(
                            outputStart,
                            result.length(),
                            region.startInclusive(),
                            region.endExclusive(),
                            safePrefixStart));
                }
            }
            cursor = region.endExclusive();
        }
        result.append(sanitizeLegacyOutputSegment(s.substring(cursor)));
        String projected = result.toString();
        String monotonic = enforceExecutableJsonProjectionMonotonicity(
                s,
                protectedExecution,
                protectedJson,
                projected,
                copiedJson,
                expectedXml);
        if (monotonic.length() != projected.length()) return monotonic;
        return stabilizeSanitizedProjection(
                s,
                protectedExecution,
                protectedJson,
                monotonic,
                copiedJson,
                expectedXml);
    }

    /**
     * Sanitizes message content for multi-turn chat (messages sent to the model).
     *
     * <p>Only strips control characters - does NOT strip HTML. Messages in the
     * tool-call pipeline may contain file content with legitimate HTML/script tags
     * (e.g., tool results from read_file). Stripping those would give the model an
     * incorrect view of the file, causing it to generate wrong edits.
     *
     * <p>This is safe for a local-first CLI where the user is the only source of
     * input. The model's output is still sanitized via
     * {@link #sanitizeForOutputPreservingToolCalls} before display.
     */
    public static String sanitizeMessageContent(String s) {
        return stripControl(s);
    }

    /**
     * Performs hard truncation to maximum character count (safe for terminal; doesn't split surrogate pairs).
     */
    public static String hardTruncate(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars);
    }

    /**
     * Performs hard truncation with callback for telemetry tracking.
     */
    public static String hardTruncate(String s, int maxChars, Runnable onTruncate) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        if (onTruncate != null) onTruncate.run();
        return s.substring(0, maxChars);
    }

    /* Back-compatibility aliases for existing code */

    /**
     * Applies suspicious-HTML stripping only to text <em>outside</em>
     * deprecated XML tool-call blocks. Recognized JSON spans are isolated
     * before this compatibility helper is called.
     *
     * <p>XML tags are DEPRECATED COMPATIBILITY support for models that
     * emit XML from training habits or cached context - not actively
     * instructed, scheduled for removal.
     *
     * <p>The algorithm: find XML tool_call blocks, protect them, strip HTML
     * from the interstitial prose, then reassemble.
     */
    private static String stripSuspiciousHtmlOutsideToolCalls(String s) {
        // Collect deprecated XML compatibility blocks.
        java.util.List<int[]> protectedRegions = new java.util.ArrayList<>();
        for (XmlBlockRegion region : xmlBlockRegions(s, true)) {
            protectedRegions.add(new int[] {
                    region.startInclusive(), region.endExclusive() });
        }

        if (protectedRegions.isEmpty()) {
            return stripSuspiciousHtmlLinear(s);
        }

        // Sort by start position
        protectedRegions.sort(java.util.Comparator.comparingInt(a -> a[0]));

        // Walk through the string, sanitizing only the gaps between blocks
        StringBuilder result = new StringBuilder(s.length());
        int lastEnd = 0;
        for (int[] region : protectedRegions) {
            int start = region[0];
            int end = region[1];
            if (start < lastEnd) continue; // overlapping region - skip
            // Sanitize prose before this block
            String before = s.substring(lastEnd, start);
            result.append(stripSuspiciousHtmlLinear(before));
            // Preserve the tool_call block verbatim
            result.append(s, start, end);
            lastEnd = end;
        }
        // Sanitize prose after the last block
        String after = s.substring(lastEnd);
        result.append(stripSuspiciousHtmlLinear(after));
        return result.toString();
    }

    private static String stripSuspiciousHtmlLinear(String source) {
        if (source == null || source.isEmpty()) return "";
        java.util.List<JsonOutputRegion> dropped = new java.util.ArrayList<>();
        java.util.List<int[]> completeSource = java.util.List.of(
                new int[] { 0, source.length() });
        addCrossingPairedTagDrops(source, completeSource, dropped);
        addCrossingEventAttributeDrops(source, completeSource, dropped);
        java.util.List<JsonOutputRegion> merged = mergeDroppedRegions(dropped);
        if (merged.isEmpty()) return source;

        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        for (JsonOutputRegion region : merged) {
            result.append(source, cursor, region.startInclusive());
            cursor = region.endExclusive();
        }
        result.append(source, cursor, source.length());
        return result.toString();
    }

    private static String sanitizeLegacyOutputSegment(String s) {
        return stripSuspiciousHtmlOutsideToolCalls(stripControl(stripThinkTags(s)));
    }

    /**
     * Fence delimiters are structural, but their surrounding bytes are not
     * selected JSON. Preserve ordinary framing exactly while translating the
     * two control-code line breaks accepted by the parser into safe LF before
     * applying the normal external-text sanitizer.
     */
    private static String sanitizeJsonEnvelopeFrame(String frame) {
        String safeLineBreaks = frame
                .replace('\u000B', '\n')
                .replace('\f', '\n');
        String withoutThink = stripThinkTags(safeLineBreaks);
        String withoutAnsi = ANSI.matcher(withoutThink).replaceAll("");
        StringBuilder withoutControls = new StringBuilder(withoutAnsi.length());
        for (int index = 0; index < withoutAnsi.length(); index++) {
            char value = withoutAnsi.charAt(index);
            boolean strippedControl = value <= '\u0008'
                    || value >= '\u000B' && value <= '\u001F'
                    || value == '\u007F';
            if (!strippedControl || value == '\r') {
                withoutControls.append(value);
            }
        }
        return stripSuspiciousHtmlOutsideToolCalls(withoutControls.toString());
    }

    private static void appendProjectedRegion(
            StringBuilder target,
            String source,
            int startInclusive,
            int endExclusive,
            JsonOutputRegionKind kind) {
        if (startInclusive >= endExclusive
                || kind == JsonOutputRegionKind.DROP) {
            return;
        }
        if (kind == JsonOutputRegionKind.COPY_FRAME) {
            target.append(sanitizeJsonEnvelopeFrame(
                    source.substring(startInclusive, endExclusive)));
            return;
        }
        target.append(source, startInclusive, endExclusive);
    }

    /**
     * Materializes the same source-ordered, non-overlapping region sequence
     * consumed by assembly. Sorting puts the widest same-start region first;
     * later overlaps are intentionally ignored by the existing fail-closed
     * projection rule.
     */
    private static java.util.List<JsonOutputRegion> effectiveProjectionRegions(
            int sourceLength,
            java.util.List<JsonOutputRegion> regions) {
        java.util.List<JsonOutputRegion> effective =
                new java.util.ArrayList<>(regions.size());
        int cursor = 0;
        for (JsonOutputRegion region : regions) {
            if (region.startInclusive() < cursor
                    || region.endExclusive() > sourceLength) {
                continue;
            }
            effective.add(region);
            cursor = region.endExclusive();
        }
        return java.util.List.copyOf(effective);
    }

    /**
     * Preserves the established precedence of hidden-thought removal over tool
     * proposal recognition. Think-tag events inside a recognized JSON range are
     * literal argument text and cannot open or close a block outside that same
     * immutable range. A complete external pair can still enclose and suppress
     * one or more proposals.
     */
    private static java.util.List<int[]> externalThinkBlocks(
            String source,
            java.util.List<ToolProtocolText.SelectedExecutableJsonSpan> selectedJson) {
        java.util.List<int[]> externalThinkBlocks = new java.util.ArrayList<>();
        java.util.regex.Matcher openings = SOURCE_THINK_OPEN.matcher(source);
        java.util.regex.Matcher closings = SOURCE_THINK_CLOSE.matcher(source);
        boolean hasOpening = openings.find();
        boolean hasClosing = closings.find();
        int externalOpenStart = -1;
        int externalDepth = 0;
        int spanIndex = 0;
        while (hasOpening || hasClosing) {
            boolean opening = hasOpening
                    && (!hasClosing || openings.start() < closings.start());
            int eventStart = opening ? openings.start() : closings.start();
            int eventEnd = opening ? openings.end() : closings.end();
            while (spanIndex < selectedJson.size()
                    && selectedJson.get(spanIndex).rootEndExclusive() <= eventStart) {
                spanIndex++;
            }
            boolean insideJson = spanIndex < selectedJson.size()
                    && eventStart >= selectedJson.get(spanIndex).rootStartInclusive()
                    && eventEnd <= selectedJson.get(spanIndex).rootEndExclusive();
            if (!insideJson) {
                if (opening) {
                    if (externalDepth == 0) {
                        externalOpenStart = eventStart;
                    }
                    externalDepth++;
                } else if (externalDepth > 0) {
                    externalDepth--;
                    if (externalDepth == 0) {
                        externalThinkBlocks.add(new int[] {
                                externalOpenStart, eventEnd });
                        externalOpenStart = -1;
                    }
                }
            }
            if (opening) {
                hasOpening = openings.find();
            } else {
                hasClosing = closings.find();
            }
        }
        if (externalDepth > 0) {
            externalThinkBlocks.add(new int[] {
                    externalOpenStart, source.length() });
        }
        return java.util.List.copyOf(externalThinkBlocks);
    }

    /**
     * Finds suspicious constructs whose external opening and closing tokens
     * lie in different sanitizable gaps. Exact JSON/frame and already-dropped
     * regions are opaque: their bytes cannot create or close an external
     * construct. Only the external slices are added to the drop set.
     */
    private static void addCrossRegionSuspiciousDrops(
            String source,
            java.util.List<JsonOutputRegion> copyRegions,
            java.util.List<JsonOutputRegion> dropped) {
        java.util.List<int[]> externalSegments = externalSegments(
                source.length(), copyRegions, dropped, source);
        addCrossingPairedTagDrops(source, externalSegments, dropped);
        addCrossingEventAttributeDrops(source, externalSegments, dropped);
    }

    private static java.util.List<int[]> externalSegments(
            int sourceLength,
            java.util.List<JsonOutputRegion> copyRegions,
            java.util.List<JsonOutputRegion> dropped,
            String source) {
        java.util.List<int[]> opaque = new java.util.ArrayList<>(
                copyRegions.size() + dropped.size());
        for (JsonOutputRegion region : copyRegions) {
            opaque.add(new int[] {
                    region.startInclusive(), region.endExclusive() });
        }
        for (JsonOutputRegion region : dropped) {
            opaque.add(new int[] {
                    region.startInclusive(), region.endExclusive() });
        }
        for (XmlBlockRegion region : xmlBlockRegions(source, true)) {
            opaque.add(new int[] {
                    region.startInclusive(), region.endExclusive() });
        }
        opaque.sort(java.util.Comparator
                .comparingInt((int[] range) -> range[0])
                .thenComparingInt(range -> range[1]));

        java.util.List<int[]> merged = new java.util.ArrayList<>(opaque.size());
        for (int[] range : opaque) {
            int start = Math.max(0, Math.min(sourceLength, range[0]));
            int end = Math.max(start, Math.min(sourceLength, range[1]));
            if (start == end) continue;
            if (merged.isEmpty() || start > merged.getLast()[1]) {
                merged.add(new int[] { start, end });
            } else {
                merged.getLast()[1] = Math.max(merged.getLast()[1], end);
            }
        }

        java.util.List<int[]> external = new java.util.ArrayList<>(
                merged.size() + 1);
        int cursor = 0;
        for (int[] range : merged) {
            if (cursor < range[0]) {
                external.add(new int[] { cursor, range[0] });
            }
            cursor = Math.max(cursor, range[1]);
        }
        if (cursor < sourceLength) {
            external.add(new int[] { cursor, sourceLength });
        }
        return java.util.List.copyOf(external);
    }

    private static void addCrossingPairedTagDrops(
            String source,
            java.util.List<int[]> externalSegments,
            java.util.List<JsonOutputRegion> dropped) {
        for (String tag : SUSPICIOUS_PAIRED_TAGS) {
            addCrossingPairedTagDrops(
                    source, externalSegments, dropped, tag);
        }
    }

    private static void addCrossingPairedTagDrops(
            String source,
            java.util.List<int[]> externalSegments,
            java.util.List<JsonOutputRegion> dropped,
            String tag) {
        boolean active = false;
        java.util.List<int[]> pending = null;
        for (int[] segment : externalSegments) {
            int cursor = segment[0];
            int sliceStart = cursor;
            while (cursor < segment[1]) {
                if (!active) {
                    DangerousTag opening = nextDangerousOpeningTag(
                            source, cursor, segment[1], tag);
                    if (opening == null) break;
                    active = true;
                    pending = new java.util.ArrayList<>();
                    sliceStart = opening.startInclusive();
                    cursor = opening.endExclusive();
                    continue;
                }

                int closeEnd = nextDangerousClosingTagEnd(
                        source, cursor, segment[1], tag);
                if (closeEnd < 0) {
                    pending.add(new int[] { sliceStart, segment[1] });
                    cursor = segment[1];
                    break;
                }
                pending.add(new int[] { sliceStart, closeEnd });
                for (int[] range : pending) {
                    dropped.add(new JsonOutputRegion(
                            range[0], range[1], JsonOutputRegionKind.DROP));
                }
                active = false;
                pending = null;
                cursor = closeEnd;
                sliceStart = cursor;
            }
            if (active && cursor >= segment[1]) {
                sliceStart = segment[1];
            }
        }
    }

    private static void addCrossingEventAttributeDrops(
            String source,
            java.util.List<int[]> externalSegments,
            java.util.List<JsonOutputRegion> dropped) {
        boolean active = false;
        java.util.List<int[]> pending = null;
        for (int[] segment : externalSegments) {
            int cursor = segment[0];
            int sliceStart = cursor;
            while (cursor < segment[1]) {
                if (!active) {
                    int[] opening = nextEventAttributeOpening(
                            source, cursor, segment[1]);
                    if (opening == null) break;
                    active = true;
                    pending = new java.util.ArrayList<>();
                    sliceStart = opening[0];
                    cursor = opening[1];
                    continue;
                }

                int closeEnd = nextEventAttributeCloseEnd(
                        source, cursor, segment[1]);
                if (closeEnd < 0) {
                    pending.add(new int[] { sliceStart, segment[1] });
                    cursor = segment[1];
                    break;
                }
                pending.add(new int[] { sliceStart, closeEnd });
                for (int[] range : pending) {
                    dropped.add(new JsonOutputRegion(
                            range[0], range[1], JsonOutputRegionKind.DROP));
                }
                active = false;
                pending = null;
                cursor = closeEnd;
                sliceStart = cursor;
            }
            if (active && cursor >= segment[1]) {
                sliceStart = segment[1];
            }
        }
    }

    private static DangerousTag nextDangerousOpeningTag(
            String source,
            int fromInclusive,
            int endExclusive,
            String tag) {
        int cursor = fromInclusive;
        while (cursor < endExclusive) {
            int opening = source.indexOf('<', cursor);
            if (opening < 0 || opening >= endExclusive) return null;
            int nameStart = opening + 1;
            while (nameStart < endExclusive
                    && isRegexWhitespace(source.charAt(nameStart))) {
                nameStart++;
            }
            int nameEnd = nameStart + tag.length();
            if (nameEnd <= endExclusive
                    && source.regionMatches(true, nameStart, tag, 0, tag.length())
                    && (nameEnd == endExclusive
                    || !isRegexWord(source.charAt(nameEnd)))) {
                int tagEnd = source.indexOf('>', nameEnd);
                if (tagEnd < 0 || tagEnd >= endExclusive) {
                    return null;
                }
                return new DangerousTag(opening, tagEnd + 1);
            }
            cursor = opening + 1;
        }
        return null;
    }

    private static int nextDangerousClosingTagEnd(
            String source,
            int fromInclusive,
            int endExclusive,
            String tag) {
        int cursor = fromInclusive;
        while (cursor < endExclusive) {
            int opening = source.indexOf('<', cursor);
            if (opening < 0 || opening >= endExclusive) return -1;
            int index = opening + 1;
            while (index < endExclusive
                    && isRegexWhitespace(source.charAt(index))) index++;
            if (index >= endExclusive || source.charAt(index) != '/') {
                cursor = opening + 1;
                continue;
            }
            index++;
            while (index < endExclusive
                    && isRegexWhitespace(source.charAt(index))) index++;
            int nameEnd = index + tag.length();
            if (nameEnd > endExclusive
                    || !source.regionMatches(true, index, tag, 0, tag.length())) {
                cursor = opening + 1;
                continue;
            }
            index = nameEnd;
            while (index < endExclusive
                    && isRegexWhitespace(source.charAt(index))) index++;
            if (index < endExclusive && source.charAt(index) == '>') {
                return index + 1;
            }
            cursor = opening + 1;
        }
        return -1;
    }

    private static int[] nextEventAttributeOpening(
            String source,
            int fromInclusive,
            int endExclusive) {
        int cursor = fromInclusive;
        while (cursor < endExclusive) {
            while (cursor < endExclusive
                    && !isRegexWord(source.charAt(cursor))) cursor++;
            int firstOpening = -1;
            char previousPrevious = 0;
            char previous = 0;
            while (cursor < endExclusive
                    && isRegexWord(source.charAt(cursor))) {
                char current = source.charAt(cursor);
                if (firstOpening < 0
                        && asciiEqualsIgnoreCase(previousPrevious, 'o')
                        && asciiEqualsIgnoreCase(previous, 'n')) {
                    firstOpening = cursor - 2;
                }
                previousPrevious = previous;
                previous = current;
                cursor++;
            }
            if (firstOpening < 0) continue;
            while (cursor < endExclusive
                    && isRegexWhitespace(source.charAt(cursor))) cursor++;
            if (cursor >= endExclusive || source.charAt(cursor) != '=') continue;
            cursor++;
            while (cursor < endExclusive
                    && isRegexWhitespace(source.charAt(cursor))) cursor++;
            if (cursor >= endExclusive
                    || source.charAt(cursor) != '\''
                    && source.charAt(cursor) != '"') continue;
            return new int[] { firstOpening, cursor + 1 };
        }
        return null;
    }

    private static boolean asciiEqualsIgnoreCase(char actual, char expectedLowercase) {
        return actual == expectedLowercase
                || actual == expectedLowercase - ('a' - 'A');
    }

    private static int nextEventAttributeCloseEnd(
            String source,
            int fromInclusive,
            int endExclusive) {
        for (int cursor = fromInclusive; cursor < endExclusive; cursor++) {
            char value = source.charAt(cursor);
            if (value == '\'' || value == '"') return cursor + 1;
        }
        return -1;
    }

    private static boolean regionMatchesIgnoreCase(
            String source,
            int start,
            String expected) {
        return start + expected.length() <= source.length()
                && source.regionMatches(
                        true, start, expected, 0, expected.length());
    }

    private static boolean isRegexWhitespace(char value) {
        return value == ' '
                || value == '\t'
                || value == '\n'
                || value == '\u000B'
                || value == '\f'
                || value == '\r';
    }

    private static boolean isRegexWord(char value) {
        return value == '_'
                || value >= '0' && value <= '9'
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z';
    }

    private static boolean isSourceLineBreak(char value) {
        return value == '\r'
                || value == '\n'
                || value == '\u000B'
                || value == '\f'
                || value == '\u0085'
                || value == '\u2028'
                || value == '\u2029';
    }

    private static java.util.List<JsonOutputRegion> mergeDroppedRegions(
            java.util.List<JsonOutputRegion> dropped) {
        if (dropped.isEmpty()) return java.util.List.of();

        dropped.sort(java.util.Comparator
                .comparingInt(JsonOutputRegion::startInclusive)
                .thenComparing(java.util.Comparator.comparingInt(
                        JsonOutputRegion::endExclusive).reversed()));
        java.util.List<JsonOutputRegion> merged = new java.util.ArrayList<>();
        int start = dropped.getFirst().startInclusive();
        int end = dropped.getFirst().endExclusive();
        for (int index = 1; index < dropped.size(); index++) {
            JsonOutputRegion next = dropped.get(index);
            if (next.startInclusive() <= end) {
                end = Math.max(end, next.endExclusive());
            } else {
                merged.add(new JsonOutputRegion(
                        start, end, JsonOutputRegionKind.DROP));
                start = next.startInclusive();
                end = next.endExclusive();
            }
        }
        merged.add(new JsonOutputRegion(
                start, end, JsonOutputRegionKind.DROP));
        return java.util.List.copyOf(merged);
    }

    /**
     * Rechecks the already-normalized projection because deleting a rejected
     * envelope, control sequence, or hidden-thought block can join two source
     * fragments into a construct that did not exist in the raw response.
     * Selected JSON roots stay opaque and byte-exact throughout every pass.
     */
    private static String stabilizeSanitizedProjection(
            String source,
            java.util.List<ToolProtocolText.SelectedExecutableJsonSpan>
                    retainedExecution,
            java.util.List<ToolProtocolText.SelectedExecutableJsonSpan>
                    retainedSource,
            String initialProjection,
            java.util.List<CopiedJsonRegion> initialCopiedJson,
            java.util.List<String> expectedXml) {
        String projected = initialProjection;
        java.util.List<CopiedJsonRegion> copiedJson = initialCopiedJson;
        for (int pass = 0; pass < MAX_PROJECTION_STABILIZATION_PASSES; pass++) {
            ToolProtocolText.JsonProposalSelection selection =
                    ToolProtocolText.selectExecutableJsonProposals(projected);
            java.util.List<JsonOutputRegion> roots = copiedJson.stream()
                    .map(copied -> new JsonOutputRegion(
                            copied.outputStartInclusive(),
                            copied.outputEndExclusive(),
                            JsonOutputRegionKind.COPY_ROOT))
                    .toList();
            java.util.List<JsonOutputRegion> dropped = new java.util.ArrayList<>();
            java.util.List<int[]> thinkBlocks = externalThinkBlocks(
                    projected, selection.sourceOrder());
            if (thinkBlocksOverlapCopiedJson(thinkBlocks, copiedJson)) {
                return "";
            }
            for (int[] block : thinkBlocks) {
                dropped.add(new JsonOutputRegion(
                        block[0], block[1], JsonOutputRegionKind.DROP));
            }
            addCrossRegionSuspiciousDrops(projected, roots, dropped);
            java.util.List<JsonOutputRegion> merged = mergeDroppedRegions(dropped);
            if (merged.isEmpty()) return projected;

            if (droppedRegionsOverlapCopiedJson(merged, copiedJson)) {
                return "";
            }
            ProjectionState next = removeProjectionRanges(
                    projected, copiedJson, merged);
            String monotonic = enforceExecutableJsonProjectionMonotonicity(
                    source,
                    retainedExecution,
                    retainedSource,
                    next.text(),
                    next.copiedJson(),
                    expectedXml);
            if (monotonic.length() != next.text().length()) return monotonic;
            projected = monotonic;
            copiedJson = next.copiedJson();
        }
        // A bounded fixed point that does not settle is not evidence of safe
        // display text. Returning nothing cannot expose or authorize protocol.
        return "";
    }

    private static boolean thinkBlocksOverlapCopiedJson(
            java.util.List<int[]> ranges,
            java.util.List<CopiedJsonRegion> copiedJson) {
        int copiedIndex = 0;
        for (int[] range : ranges) {
            while (copiedIndex < copiedJson.size()
                    && copiedJson.get(copiedIndex).outputEndExclusive()
                    <= range[0]) {
                copiedIndex++;
            }
            if (copiedIndex < copiedJson.size()
                    && copiedJson.get(copiedIndex).outputStartInclusive()
                    < range[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean droppedRegionsOverlapCopiedJson(
            java.util.List<JsonOutputRegion> ranges,
            java.util.List<CopiedJsonRegion> copiedJson) {
        int copiedIndex = 0;
        for (JsonOutputRegion range : ranges) {
            while (copiedIndex < copiedJson.size()
                    && copiedJson.get(copiedIndex).outputEndExclusive()
                    <= range.startInclusive()) {
                copiedIndex++;
            }
            if (copiedIndex < copiedJson.size()
                    && copiedJson.get(copiedIndex).outputStartInclusive()
                    < range.endExclusive()) {
                return true;
            }
        }
        return false;
    }

    private static ProjectionState removeProjectionRanges(
            String projected,
            java.util.List<CopiedJsonRegion> copiedJson,
            java.util.List<JsonOutputRegion> dropped) {
        StringBuilder retained = new StringBuilder(projected.length());
        int cursor = 0;
        for (JsonOutputRegion range : dropped) {
            retained.append(projected, cursor, range.startInclusive());
            cursor = range.endExclusive();
        }
        retained.append(projected, cursor, projected.length());

        DropBoundaryMapper boundaryMapper = new DropBoundaryMapper(dropped);
        java.util.List<CopiedJsonRegion> remapped =
                new java.util.ArrayList<>(copiedJson.size());
        for (CopiedJsonRegion copied : copiedJson) {
            int safePrefixStart = boundaryMapper.map(
                    copied.safePrefixOutputStartInclusive());
            int outputStart = boundaryMapper.map(
                    copied.outputStartInclusive());
            int outputEnd = boundaryMapper.map(
                    copied.outputEndExclusive());
            remapped.add(new CopiedJsonRegion(
                    outputStart,
                    outputEnd,
                    copied.sourceStartInclusive(),
                    copied.sourceEndExclusive(),
                    safePrefixStart));
        }
        return new ProjectionState(retained.toString(), remapped);
    }

    private static final class DropBoundaryMapper {
        private final java.util.List<JsonOutputRegion> dropped;
        private int droppedIndex;
        private int removed;
        private int lastBoundary = -1;

        private DropBoundaryMapper(java.util.List<JsonOutputRegion> dropped) {
            this.dropped = dropped;
        }

        private int map(int boundary) {
            if (boundary < lastBoundary) {
                throw new IllegalStateException(
                        "JSON projection boundaries are not source ordered");
            }
            lastBoundary = boundary;
            while (droppedIndex < dropped.size()
                    && dropped.get(droppedIndex).endExclusive() <= boundary) {
                JsonOutputRegion range = dropped.get(droppedIndex++);
                removed += range.endExclusive() - range.startInclusive();
            }
            if (droppedIndex < dropped.size()
                    && dropped.get(droppedIndex).startInclusive() < boundary) {
                return dropped.get(droppedIndex).startInclusive() - removed;
            }
            return boundary - removed;
        }
    }

    /**
     * Enforces the central T1194 monotonicity invariant: output sanitation may
     * retain exact recognized source spans, but it may never create, move,
     * merge, or rewrite an executable JSON span.
     */
    private static String enforceExecutableJsonProjectionMonotonicity(
            String source,
            java.util.List<ToolProtocolText.SelectedExecutableJsonSpan>
                    retainedExecution,
            java.util.List<ToolProtocolText.SelectedExecutableJsonSpan>
                    retainedSource,
            String projected,
            java.util.List<CopiedJsonRegion> copiedJson,
            java.util.List<String> expectedXml) {
        if (!recognizedXmlBlocks(projected).equals(expectedXml)) {
            return "";
        }
        ToolProtocolText.JsonProposalSelection projectedSelection =
                ToolProtocolText.selectExecutableJsonProposals(projected);
        java.util.List<ToolProtocolText.SelectedExecutableJsonSpan> projectedSource =
                projectedSelection.sourceOrder();
        int shared = Math.min(
                projectedSource.size(),
                Math.min(retainedSource.size(), copiedJson.size()));
        for (int index = 0; index < shared; index++) {
            ToolProtocolText.SelectedExecutableJsonSpan sourceSpan =
                    retainedSource.get(index);
            ToolProtocolText.SelectedExecutableJsonSpan outputSpan =
                    projectedSource.get(index);
            CopiedJsonRegion copied = copiedJson.get(index);
            if (outputSpan.rootStartInclusive() != copied.outputStartInclusive()
                    || outputSpan.rootEndExclusive() != copied.outputEndExclusive()
                    || outputSpan.kind() != sourceSpan.kind()
                    || copied.sourceStartInclusive() != sourceSpan.rootStartInclusive()
                    || copied.sourceEndExclusive() != sourceSpan.rootEndExclusive()
                    || !source.regionMatches(
                            sourceSpan.rootStartInclusive(),
                            projected,
                            outputSpan.rootStartInclusive(),
                            sourceSpan.rootEndExclusive()
                                    - sourceSpan.rootStartInclusive())) {
                return "";
            }
        }
        if (projectedSource.size() > shared) {
            return "";
        }
        if (copiedJson.size() > shared) {
            return "";
        }
        if (retainedSource.size() != shared) {
            return "";
        }

        java.util.List<ToolProtocolText.SelectedExecutableJsonSpan>
                projectedCandidates = projectedSelection.recognizedCandidates();
        int sharedCandidates = Math.min(
                projectedCandidates.size(), copiedJson.size());
        for (int index = 0; index < sharedCandidates; index++) {
            ToolProtocolText.SelectedExecutableJsonSpan candidate =
                    projectedCandidates.get(index);
            ToolProtocolText.SelectedExecutableJsonSpan sourceSpan =
                    retainedSource.get(index);
            CopiedJsonRegion copied = copiedJson.get(index);
            if (candidate.rootStartInclusive() != copied.outputStartInclusive()
                    || candidate.rootEndExclusive() != copied.outputEndExclusive()
                    || candidate.kind() != sourceSpan.kind()) {
                return "";
            }
        }
        if (projectedCandidates.size() > sharedCandidates) {
            return "";
        }
        if (copiedJson.size() > sharedCandidates) {
            return "";
        }

        java.util.List<ToolProtocolText.SelectedExecutableJsonSpan> projectedExecution =
                projectedSelection.executionOrder();
        if (projectedExecution.size() != retainedExecution.size()) {
            return "";
        }
        for (int index = 0; index < retainedExecution.size(); index++) {
            ToolProtocolText.SelectedExecutableJsonSpan sourceSpan =
                    retainedExecution.get(index);
            ToolProtocolText.SelectedExecutableJsonSpan outputSpan =
                    projectedExecution.get(index);
            if (sourceSpan.kind() != outputSpan.kind()
                    || !sourceSpan.rootFrom(source).equals(
                            outputSpan.rootFrom(projected))) {
                return "";
            }
        }
        return projected;
    }

    private static java.util.List<String> expectedRecognizedXmlBlocks(
            String source,
            java.util.List<ToolProtocolText.SourceRange> excludedRanges,
            java.util.List<JsonOutputRegion> projectionRegions) {
        java.util.List<ToolProtocolText.SourceRange> excluded = excludedRanges.stream()
                .sorted(java.util.Comparator
                        .comparingInt(ToolProtocolText.SourceRange::startInclusive)
                        .thenComparingInt(
                                ToolProtocolText.SourceRange::endExclusive))
                .toList();
        java.util.List<String> expected = new java.util.ArrayList<>();
        int excludedIndex = 0;
        int projectionIndex = 0;
        for (XmlBlockRegion region : xmlBlockRegions(source, false)) {
            while (excludedIndex < excluded.size()
                    && excluded.get(excludedIndex).endExclusive()
                    <= region.startInclusive()) {
                excludedIndex++;
            }
            if (excludedIndex < excluded.size()
                    && excluded.get(excludedIndex).startInclusive()
                    < region.endExclusive()) {
                continue;
            }
            String block = source.substring(
                    region.startInclusive(), region.endExclusive());
            if (!ToolProtocolText.containsRecognizedXmlToolCall(block)) continue;
            while (projectionIndex < projectionRegions.size()
                    && projectionRegions.get(projectionIndex).endExclusive()
                    <= region.startInclusive()) {
                projectionIndex++;
            }
            String projectedBlock = projectSourceRangeWithPlan(
                    source,
                    region.startInclusive(),
                    region.endExclusive(),
                    projectionRegions,
                    projectionIndex);
            expected.addAll(recognizedXmlBlocks(
                    projectedBlock));
        }
        return java.util.List.copyOf(expected);
    }

    /**
     * Projects one raw-eligible XML source range through the exact same region
     * ownership and transforms as the main assembler. Raw XML eligibility is
     * established before this step, so normalization cannot manufacture a new
     * compatibility block.
     */
    private static String projectSourceRangeWithPlan(
            String source,
            int startInclusive,
            int endExclusive,
            java.util.List<JsonOutputRegion> regions,
            int regionStartIndex) {
        StringBuilder projected = new StringBuilder(
                Math.max(0, endExclusive - startInclusive));
        int cursor = startInclusive;
        for (int regionIndex = regionStartIndex;
                regionIndex < regions.size();
                regionIndex++) {
            JsonOutputRegion region = regions.get(regionIndex);
            if (region.endExclusive() <= cursor) continue;
            if (region.startInclusive() >= endExclusive) break;
            int regionStart = Math.max(cursor, region.startInclusive());
            int regionEnd = Math.min(endExclusive, region.endExclusive());
            projected.append(sanitizeLegacyOutputSegment(
                    source.substring(cursor, regionStart)));
            appendProjectedRegion(
                    projected,
                    source,
                    regionStart,
                    regionEnd,
                    region.kind());
            cursor = regionEnd;
        }
        projected.append(sanitizeLegacyOutputSegment(
                source.substring(cursor, endExclusive)));
        return projected.toString();
    }

    private static java.util.List<String> recognizedXmlBlocks(String source) {
        java.util.List<String> recognized = new java.util.ArrayList<>();
        for (XmlBlockRegion region : xmlBlockRegions(source, false)) {
            String block = source.substring(
                    region.startInclusive(), region.endExclusive());
            if (ToolProtocolText.containsRecognizedXmlToolCall(block)) {
                recognized.add(block);
            }
        }
        return java.util.List.copyOf(recognized);
    }

    private record JsonOutputRegion(
            int startInclusive,
            int endExclusive,
            JsonOutputRegionKind kind,
            int ownerIndex) {
        private JsonOutputRegion(
                int startInclusive,
                int endExclusive,
                JsonOutputRegionKind kind) {
            this(startInclusive, endExclusive, kind, -1);
        }
    }

    private enum JsonOutputRegionKind {
        DROP,
        COPY_FRAME,
        COPY_ROOT
    }

    private record CopiedJsonRegion(
            int outputStartInclusive,
            int outputEndExclusive,
            int sourceStartInclusive,
            int sourceEndExclusive,
            int safePrefixOutputStartInclusive) {}

    private record ProjectionState(
            String text,
            java.util.List<CopiedJsonRegion> copiedJson) {}

    private record DangerousTag(
            int startInclusive,
            int endExclusive) {}

    private static java.util.List<XmlBlockRegion> xmlBlockRegions(
            String source,
            boolean protectionOnly) {
        java.util.List<String> openers = protectionOnly
                ? XML_PROTECTED_OPENERS
                : XML_PROTOCOL_OPENERS;
        java.util.List<String> closers = protectionOnly
                ? XML_PROTECTED_CLOSERS
                : XML_PROTOCOL_CLOSERS;
        java.util.List<XmlBlockRegion> regions = new java.util.ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            TokenMatch opening = earliestToken(source, openers, cursor);
            if (opening == null) break;
            TokenMatch closing = earliestToken(
                    source, closers, opening.endExclusive());
            if (closing == null) break;
            regions.add(new XmlBlockRegion(
                    opening.startInclusive(), closing.endExclusive()));
            cursor = closing.endExclusive();
        }
        return java.util.List.copyOf(regions);
    }

    private static TokenMatch earliestToken(
            String source,
            java.util.List<String> tokens,
            int fromInclusive) {
        int cursor = Math.max(0, fromInclusive);
        while (cursor < source.length()) {
            int candidate = source.indexOf('<', cursor);
            if (candidate < 0) return null;
            for (String token : tokens) {
                if (source.regionMatches(
                        candidate, token, 0, token.length())) {
                    return new TokenMatch(
                            candidate, candidate + token.length());
                }
            }
            cursor = candidate + 1;
        }
        return null;
    }

    private record XmlBlockRegion(int startInclusive, int endExclusive) {}

    private record TokenMatch(int startInclusive, int endExclusive) {}

    /**
     * Legacy alias: removes ANSI escape sequences only.
     */
    public static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) return "";
        return ANSI.matcher(s).replaceAll("");
    }

    /**
     * Legacy alias: removes control characters and nulls.
     */
    public static String stripControls(String s) {
        if (s == null || s.isEmpty()) return "";
        return CTRL.matcher(s).replaceAll("");
    }

    /**
     * Legacy alias: removes &lt;think&gt; tags with Unicode escape decoding.
     */
    public static String stripThinkTags(String s) {
        if (s == null || s.isEmpty()) return s;

        // First, Unicode escapes are decoded (< -> <, > -> >)
        s = s.replace("\\u003c", "<").replace("\\u003e", ">");

        // Then <think>...</think> blocks are removed (case-insensitive)
        s = s.replaceAll("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", "");

        // Stray open/close think tags are removed
        s = s.replaceAll("(?is)<\\s*/?\\s*think\\s*>", "");

        return s;
    }
}
