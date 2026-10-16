package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContractResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Original-source operation facts. Recognition does not grant execution permission. */
public record ContentSaveRequest(String originalRequest, List<Operation> operations,
                                 ClarificationBinding clarificationBinding, DraftSelection draftSelection) {
    private static final int MAX_REQUEST_CHARACTERS = 8192;
    private static final Pattern NAMED_DESTINATION = Pattern.compile(
            "(?iu)\\b(?:named|called|as)\\s+");
    private static final Pattern OUTPUT_MARKER = Pattern.compile("(?iu)\\b(?:named|called|as|to|into|in|at)\\s+");
    private static final String FILE_OBJECT = "(?:(?:a|an|the|new|text|txt)\\s+)*file";
    private static final Pattern FILE_OBJECT_HEAD = Pattern.compile("(?iu)^\\s*" + FILE_OBJECT + "\\b");
    private static final Pattern FILE_NAME_SLOT = Pattern.compile(
            "(?iu)^\\s*" + FILE_OBJECT + "\\s+(?:named|called|as|to|into|in|at)\\s+$");
    private static final Pattern TRAILING_FILE_NAME_SLOT = Pattern.compile(
            "(?iu)\\b(?:in|into|to)\\s+" + FILE_OBJECT + "\\s+(?:named|called|at)\\s+$");
    private static final Pattern PATH = Pattern.compile(
            "[\\p{L}\\p{N}_./\\\\-]+(?:\\.[\\p{L}\\p{N}_-]+)?");
    private static final Pattern CONTENT_DESCRIPTION = Pattern.compile(
            "(?iu)\\b(?:with|containing|contain|about)\\s+\\S");
    private static final Pattern FILE_DESCRIPTION = Pattern.compile(
            "(?iu)\\b(?:(?:a|an|the|new|text|txt)\\s+){0,3}file\\b");
    private static final Pattern CONTENT_PRONOUN = Pattern.compile(
            "(?iu)^\\s*(?:it|this|that|them|these|those)\\b");
    private static final Pattern LITERAL_INTRODUCER = Pattern.compile(
            "(?iu)\\s*(?:containing|contain|with(?:\\s+(?:the\\s+)?(?:content|text))?)\\s+(?:exactly\\s*)?:?\\s*");

    public enum Authority { REQUIRED, OPTIONAL, NON_AUTHORITATIVE, FORBIDDEN }
    public enum Binding { RESOLVED, MISSING, AMBIGUOUS }

    public ContentSaveRequest(String originalRequest, List<Operation> operations) {
        this(originalRequest, operations, null);
    }

    public ContentSaveRequest(String originalRequest, List<Operation> operations, ClarificationBinding binding) {
        this(originalRequest, operations, binding, null);
    }

    /** Selected proposal bytes are not attributed to an original source span. */
    public record DraftSelection(int operationIndex, String id, String destination, String content,
                                 int originTurn, int selectionTurn) {
        public DraftSelection {
            if (operationIndex < 0 || id == null || !id.matches("[a-f0-9]{12}")
                    || destination == null || destination.isBlank() || content == null || content.isBlank()
                    || !destination.equals(TargetRef.normalizePath(destination)) || !validDraftContent(content)
                    || originTurn < 1 || selectionTurn <= originTurn || selectionTurn > originTurn + 5) {
                throw new IllegalArgumentException("Invalid selected content draft");
            }
        }
    }

    /** The filename reply has its own coordinate base and grants no approval. */
    public record ClarificationBinding(int operationIndex, String replyRequest, SourceSpan replySpan,
                                       String destination, int originTurn, int replyTurn) {
        public ClarificationBinding {
            if (operationIndex < 0 || replyRequest == null || replySpan == null || destination == null
                    || replySpan.end() > replyRequest.length() || originTurn < 1 || replyTurn <= originTurn
                    || !TargetRef.normalizePath(replySpan.text(replyRequest)).equals(destination)) {
                throw new IllegalArgumentException("Invalid save clarification binding");
            }
        }
    }

    public record SourceSpan(int start, int end) {
        public SourceSpan {
            if (start < 0 || end < start) throw new IllegalArgumentException("invalid source span");
        }
        public String text(String request) { return request.substring(start, end); }
    }

    public record Operand(Binding binding, String value, SourceSpan source) {
        public Operand {
            if (binding == null || source == null) throw new IllegalArgumentException("missing operand metadata");
            value = value == null ? "" : value;
            if (binding == Binding.RESOLVED && value.isBlank()) {
                throw new IllegalArgumentException("resolved operand needs a value");
            }
        }
        public boolean resolved() { return binding == Binding.RESOLVED; }
    }

    public record Operation(SourceSpan source, SourceSpan action, Authority authority,
                            Operand destination, Operand content) {
        public Operation {
            if (source == null || action == null || authority == null || destination == null || content == null
                    || action.start() < source.start() || action.end() > source.end()) {
                throw new IllegalArgumentException("invalid save operation");
            }
        }
        public boolean ready() { return destination.resolved() && content.resolved(); }
    }

    public ContentSaveRequest {
        originalRequest = originalRequest == null ? "" : originalRequest;
        operations = operations == null ? List.of() : List.copyOf(operations);
        for (Operation operation : operations) {
            if (operation.source().end() > originalRequest.length()
                    || operation.destination().source().end() > originalRequest.length()
                    || operation.content().source().end() > originalRequest.length()) {
                throw new IllegalArgumentException("save operand exceeds original request");
            }
        }
        if (clarificationBinding != null) {
            int index = clarificationBinding.operationIndex();
            if (index >= operations.size()) throw new IllegalArgumentException("Unknown clarified operation");
            Operation operation = operations.get(index);
            if (operation.authority() != Authority.REQUIRED || !operation.content().resolved()
                    || operation.destination().binding() != Binding.MISSING
                    || operations.stream().filter(op -> op.authority() == Authority.REQUIRED).count() != 1) {
                throw new IllegalArgumentException("Clarification must bind one missing destination with known content");
            }
        }
        if (draftSelection != null) {
            int index = draftSelection.operationIndex();
            if (clarificationBinding != null || index >= operations.size()
                    || operations.stream().filter(op -> op.authority() == Authority.REQUIRED).count() != 1) {
                throw new IllegalArgumentException("A draft must select one unresolved save");
            }
            Operation operation = operations.get(index);
            if (operation.authority() != Authority.REQUIRED || operation.content().binding() != Binding.MISSING
                    || operation.destination().binding() == Binding.AMBIGUOUS
                    || operation.destination().resolved()
                    && !TargetRef.normalizePath(operation.destination().value()).equals(draftSelection.destination())) {
                throw new IllegalArgumentException("Draft selection differs from the unresolved operation");
            }
        }
    }

    public static ContentSaveRequest empty(String request) { return new ContentSaveRequest(request, List.of()); }
    public boolean present() { return !operations.isEmpty(); }
    public boolean hasIncompleteQuotedContent() {
        return RequestLexicalView.of(originalRequest).containsIncompleteQuotedEnvelope();
    }

    public static boolean validDraftContent(String text) {
        if (text == null || text.isBlank() || text.length() > 8192
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_384) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c) && c != '\n' && c != '\t'
                    || Character.getType(c) == Character.FORMAT) return false;
        }
        return true;
    }
    public boolean mutationRequested() {
        return operations.stream().anyMatch(operation -> operation.authority() == Authority.REQUIRED);
    }
    public boolean clarificationRequired() {
        for (int index = 0; index < operations.size(); index++) {
            Operation operation = operations.get(index);
            if (operation.authority() == Authority.REQUIRED && !operation.ready()
                    && (clarificationBinding == null || clarificationBinding.operationIndex() != index)
                    && (draftSelection == null || draftSelection.operationIndex() != index)) return true;
        }
        return false;
    }
    public Set<String> resolvedTargets() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        operations.stream().filter(operation -> operation.authority() == Authority.REQUIRED)
                .filter(operation -> operation.destination().resolved())
                .forEach(operation -> targets.add(operation.destination().value()));
        if (clarificationBinding != null) targets.add(clarificationBinding.destination());
        if (draftSelection != null) targets.add(draftSelection.destination());
        return Set.copyOf(targets);
    }

    public ContentSaveRequest bindDestination(ClarificationBinding binding) {
        if (clarificationBinding != null || draftSelection != null) throw new IllegalStateException("Save destination was already clarified");
        return new ContentSaveRequest(originalRequest, operations, binding);
    }

    public ContentSaveRequest selectDraft(DraftSelection selection) {
        if (clarificationBinding != null || draftSelection != null) throw new IllegalStateException("Save already bound");
        return new ContentSaveRequest(originalRequest, operations, null, selection);
    }

    /** Only operand readiness is established. Whole request coverage remains unassessed. */
    public boolean supportsFilenameContinuation() {
        if (clarificationBinding != null || originalRequest.length() > MAX_REQUEST_CHARACTERS) return false;
        List<Operation> required = operations.stream()
                .filter(operation -> operation.authority() == Authority.REQUIRED).toList();
        return required.size() == 1 && required.getFirst().content().resolved()
                && required.getFirst().destination().binding() == Binding.MISSING;
    }

    /**
     * Lexical status candidates, not general semantic reference proof. Only the leading content
     * phrase and its bounded suffixes are admitted. Nested subjects after a preposition are not.
     * A selected answer must identify the known pending source turn, and full-request status
     * grammar must still cover the current question. Unknown references use the normal route.
     */
    public Set<String> continuationReferents() {
        LinkedHashSet<String> referents = new LinkedHashSet<>(List.of("it", "this", "that",
                "the pending save", "the earlier save", "the requested save", "the save request",
                "the requested content"));
        RequestLexicalView view = RequestLexicalView.of(originalRequest);
        for (Operation operation : operations) {
            if (operation.authority() != Authority.REQUIRED || !operation.content().resolved()) continue;
            int start = Math.max(operation.action().end(), operation.content().source().start());
            int end = operation.content().source().end();
            if (start >= end) continue;
            String description = view.masked().substring(start, end)
                    .split("(?iu)[,.!?;\\r\\n]|\\b(?:and|but|then)\\b", 2)[0]
                    .replaceFirst("(?iu)^\\s*(?:(?:a|an|the|new|text|txt)\\s+){0,3}file\\s+(?:with|containing)\\s+", "")
                    .split("(?iu)\\b(?:about|in|for|on|of|by|with|containing)\\b", 2)[0]
                    .strip().toLowerCase(java.util.Locale.ROOT);
            if (!description.matches("[\\p{L}\\p{N} -]+")) continue;
            String[] words = description.split("\\s+");
            for (int index = Math.max(0, words.length - 8); index < words.length; index++) {
                String phrase = String.join(" ", java.util.Arrays.copyOfRange(words, index, words.length))
                        .replaceFirst("^(?:a|an|the) ", "");
                if (phrase.length() > 100 || Set.of("file", "files", "txt", "text", "work", "task", "content")
                        .contains(phrase) || phrase.isBlank()) continue;
                referents.add("the " + phrase);
            }
        }
        return Set.copyOf(referents);
    }

    /** Migrated nonrequired operations cannot be promoted again by the legacy whole-request label. */
    public boolean restrictsLegacyMutation() {
        if (!present() || mutationRequested()) return false;
        RequestLexicalView view = RequestLexicalView.of(originalRequest);
        for (var action : CanonicalMutationClauseMatcher.actions(view, 0, originalRequest.length())) {
            if (action.family() != CanonicalMutationClauseMatcher.Family.CONTENT_MUTATION
                    || action.nonImperativeUse()
                    || action.scopeKind() != CanonicalMutationClauseMatcher.ScopeKind.NONE) continue;
            boolean represented = operations.stream().anyMatch(operation ->
                    operation.action().start() == action.start()
                    || operation.action().start() > action.end()
                    && view.masked().substring(action.end(), operation.action().start())
                    .matches("(?iu)^\\s*(?:and|then)\\s*$"));
            if (!represented) return false;
        }
        return true;
    }

    /** Quoted data cannot introduce a whole-request mutation prohibition. */
    public boolean hasUnquotedGlobalReadOnlyProhibition() {
        return "global-read-only-negation".equals(dev.talos.runtime.MutationIntent.classificationReason(
                RequestLexicalView.of(originalRequest).masked()));
    }

    /** Mask only source-owned literal payloads, leaving quoted path operands intact. */
    public String withoutQuotedLiteralPayloads() {
        RequestLexicalView view = RequestLexicalView.of(originalRequest);
        StringBuilder visible = new StringBuilder(originalRequest);
        for (Operation operation : operations) {
            SourceSpan source = operation.content().source();
            if (!operation.content().resolved() || source.start() == source.end()) continue;
            boolean literal = true;
            for (int offset = source.start(); offset < source.end(); offset++) {
                if (!view.isQuoted(offset)) { literal = false; break; }
            }
            if (literal) {
                for (int offset = source.start(); offset < source.end(); offset++) visible.setCharAt(offset, ' ');
            }
        }
        return visible.toString();
    }

    /** Only content-label occurrences are excluded from target discovery, not source or permission checks. */
    public String targetDiscoveryRequest() {
        RequestLexicalView view = RequestLexicalView.of(originalRequest);
        StringBuilder visible = new StringBuilder(withoutQuotedLiteralPayloads());
        for (Operation operation : operations) {
            int from = operation.action().end();
            int end = operation.source().end();
            Matcher marker = NAMED_DESTINATION.matcher(originalRequest).region(from, end);
            while (marker.find()) {
                if (!view.isUnquotedRange(marker.start(), marker.end())) continue;
                Operand operand = namedOperand(view, marker.end(), end);
                if (operand == null || destinationMarkerOwned(view, from, end, marker,
                        operation.destination().resolved() ? operation.destination() : null)) continue;
                for (int offset = operand.source().start(); offset < operand.source().end(); offset++) {
                    visible.setCharAt(offset, ' ');
                }
            }
        }
        return visible.toString();
    }

    public static ContentSaveRequest parse(String request) {
        String original = request == null ? "" : request;
        if (original.isBlank() || original.length() > MAX_REQUEST_CHARACTERS) return empty(original);
        RequestLexicalView view = RequestLexicalView.of(original);
        if (view.containsProviderRemovedControl(0, original.length())
                || view.containsLoneCarriageReturn(0, original.length())
                || view.containsMalformedDelimiter(0, original.length())) return empty(original);
        List<CanonicalMutationClauseMatcher.Action> actions = CanonicalMutationClauseMatcher.actions(view, 0, original.length());
        LinkedHashSet<String> candidates = new LinkedHashSet<>(TaskContractResolver.extractExpectedTargets(original));
        candidates.addAll(CoordinatedMutationTailParser.discoverAllDirectTargets(original));
        List<Operation> operations = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            var action = actions.get(index);
            if (!Set.of("save", "write", "create", "put").contains(action.verb())
                    || !view.isTopLevel(action.start())) continue;
            int start = view.sentenceStartBefore(0, action.start());
            int end = view.sentenceEndAfter(action.end());
            if (index + 1 < actions.size()) end = Math.min(end, actions.get(index + 1).start());
            var outputBinding = CoordinatedMutationTailParser.contentOutputBinding(
                    view, candidates, action, end, actions);
            if (outputBinding.workspaceOwned()) continue;
            Authority authority = authority(action, view, end);
            if (index > 0) {
                var previous = actions.get(index - 1);
                String connector = view.masked().substring(previous.end(), action.start());
                if (connector.matches("(?iu)^[^;.!?\\r\\n]*\\b(?:and|then)\\s*$")) {
                    Authority governing = authority(previous, view, previous.end());
                    if (governing != Authority.REQUIRED) authority = governing;
                }
            }
            Operand destination = destination(view, action, end, outputBinding);
            String remainder = view.masked().substring(action.end(), end);
            boolean boundCreationOutput = "create".equals(action.verb()) && destination.resolved()
                    && ownsDestination(view, end, outputBinding, destination);
            if (!"save".equals(action.verb())
                    && !(FILE_DESCRIPTION.matcher(remainder).find()
                    || boundCreationOutput
                    || "create".equals(action.verb()) && destination.resolved()
                            && CONTENT_PRONOUN.matcher(remainder).find()
                    || destination.resolved() && CONTENT_DESCRIPTION.matcher(remainder).find()
                    || view.exactQuotedContentStartingAt(skipWhitespace(original, action.end(), end), end) != null)) {
                continue;
            }
            if (!"save".equals(action.verb()) && index + 1 < actions.size()
                    && "save".equals(actions.get(index + 1).verb())
                    && view.masked().substring(action.end(), end).matches("(?iu)^\\s*(?:and|then)\\s*$")) {
                continue;
            }
            Operand content = content(view, start, action.start(), action.end(), end, destination,
                    "create".equals(action.verb()));
            if (authority == Authority.REQUIRED && !destination.resolved()
                    && view.masked().substring(start, action.start()).matches(
                    "(?isu)^\\s*(?:can|could|would)\\s+you\\b.*")
                    && !CONTENT_DESCRIPTION.matcher(remainder).find()) {
                authority = Authority.NON_AUTHORITATIVE;
            }
            operations.add(new Operation(new SourceSpan(start, end), new SourceSpan(action.start(), action.end()),
                    authority, destination, content));
        }
        return new ContentSaveRequest(original, operations);
    }

    private static Authority authority(CanonicalMutationClauseMatcher.Action action, RequestLexicalView view, int end) {
        var postfix = CanonicalMutationClauseMatcher.finitePostfixScopeKind(view.masked().substring(action.end(), end));
        if (action.scopeKind() == CanonicalMutationClauseMatcher.ScopeKind.PROHIBITED
                || postfix == CanonicalMutationClauseMatcher.ScopeKind.PROHIBITED) return Authority.FORBIDDEN;
        if (action.nonImperativeUse() || action.scopeKind() == CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY
                || postfix == CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY) return Authority.NON_AUTHORITATIVE;
        if (action.scopeKind() == CanonicalMutationClauseMatcher.ScopeKind.OPTIONAL
                || postfix == CanonicalMutationClauseMatcher.ScopeKind.OPTIONAL) return Authority.OPTIONAL;
        return Authority.REQUIRED;
    }

    private static Operand destination(RequestLexicalView view, CanonicalMutationClauseMatcher.Action action,
                                       int end,
                                       CoordinatedMutationTailParser.ContentOutputBinding outputBinding) {
        int from = action.end();
        List<Operand> operands = new ArrayList<>();
        for (var occurrence : outputBinding.targets()) {
            String lead = view.masked().substring(from, occurrence.envelopeStart());
            // A leading literal is payload unless its syntax independently identifies a path operand.
            if (occurrence.quoted() && lead.isBlank() && !"create".equals(action.verb())
                    && !(occurrence.path().matches("[\\p{L}\\p{N}_./\\\\-]+")
                    && occurrence.path().matches(".*[./\\\\].*")
                    && !occurrence.path().endsWith("."))) continue;
            operands.add(outputOperand(view, occurrence, end));
        }
        Matcher named = OUTPUT_MARKER.matcher(view.request());
        named.region(from, end);
        while (named.find()) {
            if (!view.isUnquotedRange(named.start(), named.end())) continue;
            Operand operand = namedOperand(view, named.end(), end);
            if (operand != null && destinationMarkerOwned(view, from, end, named,
                    operands.isEmpty() ? null : operands.getFirst())) operands.add(operand);
        }
        return selectDestination(view, from, end, operands);
    }

    private static boolean destinationMarkerOwned(RequestLexicalView view, int from, int end,
                                                  Matcher marker, Operand bound) {
        if (bound != null && bound.source().start() >= marker.end()
                && view.request().substring(marker.end(), bound.source().start()).matches("[\\s`'\"]*")) return true;
        String lead = view.masked().substring(from, marker.end());
        if (FILE_NAME_SLOT.matcher(lead).matches()) return true;
        if (FILE_OBJECT_HEAD.matcher(view.masked().substring(from, end)).find()) return false;
        if (TRAILING_FILE_NAME_SLOT.matcher(lead).find()) return true;
        if (!Set.of("as", "to", "into", "in", "at").contains(marker.group().strip().toLowerCase(java.util.Locale.ROOT))
                || bound != null) return false;
        Operand operand = namedOperand(view, marker.end(), end);
        if (operand == null || !operand.value().matches(".*[./\\\\].*")
                && view.exactQuotedContentStartingAt(marker.end(), end) == null) return false;
        // An opaque nested description cannot establish attachment of an as complement.
        if (marker.group().strip().equalsIgnoreCase("as")
                && CONTENT_DESCRIPTION.matcher(view.masked().substring(from, marker.start())).find()) {
            Matcher label = NAMED_DESTINATION.matcher(view.request()).region(from, marker.start());
            Operand lastLabel = null;
            while (label.find()) {
                if (view.isUnquotedRange(label.start(), label.end())) lastLabel = namedOperand(view, label.end(), marker.start());
            }
            if (lastLabel == null || !view.masked().substring(lastLabel.source().end(), marker.start())
                    .matches("[\\s`'\"]*")) return false;
        }
        String tail = view.masked().substring(operand.source().end(), end);
        return tail.matches("(?isu)^[\\s`'\".!?]*(?:(?:,\\s*)?(?:or|and/or|but|and|then)\\b.*)?$");
    }

    private static Operand namedOperand(RequestLexicalView view, int start, int end) {
        RequestLexicalView.Span quoted = view.exactQuotedContentStartingAt(start, end);
        int finish;
        if (quoted != null) { start = quoted.start(); finish = quoted.end(); }
        else {
            Matcher path = PATH.matcher(view.request()).region(start, end);
            if (!path.lookingAt()) return null;
            finish = path.end();
            while (finish > start && ".,;:!?".indexOf(view.request().charAt(finish - 1)) >= 0) finish--;
        }
        String value = view.request().substring(start, finish);
        if (value.isBlank() || Set.of("it", "this", "that", "there", "here", "a", "the")
                .contains(value.toLowerCase(java.util.Locale.ROOT))) return null;
        return new Operand(Binding.RESOLVED, value.replace('\\', '/'), new SourceSpan(start, finish));
    }

    private static boolean ownsDestination(RequestLexicalView view, int end,
                                           CoordinatedMutationTailParser.ContentOutputBinding outputBinding,
                                           Operand destination) {
        return outputBinding.targets().stream().anyMatch(occurrence ->
                outputOperand(view, occurrence, end).equals(destination));
    }

    private static Operand outputOperand(RequestLexicalView view,
                                         CoordinatedMutationTailParser.TargetOccurrence occurrence, int end) {
        int start = occurrence.envelopeStart();
        int finish = occurrence.envelopeEnd();
        RequestLexicalView.Span quoted = view.exactQuotedContentStartingAt(start, end);
        if (quoted != null) { start = quoted.start(); finish = quoted.end(); }
        return new Operand(Binding.RESOLVED, occurrence.path(), new SourceSpan(start, finish));
    }

    private static Operand selectDestination(RequestLexicalView view, int from, int end, List<Operand> operands) {
        List<Operand> unique = operands.stream().distinct().toList();
        if (unique.isEmpty()) return new Operand(Binding.MISSING, "", new SourceSpan(from, end));
        Set<String> paths = new LinkedHashSet<>();
        unique.forEach(operand -> paths.add(operand.value()));
        if (paths.size() > 1 || view.masked().substring(unique.getFirst().source().end(), end)
                .matches("(?isu)^\\s*(?:,\\s*)?(?:or|and/or)\\b.*")) {
            return new Operand(Binding.AMBIGUOUS, "", new SourceSpan(from, end));
        }
        return unique.getFirst();
    }

    private static Operand content(RequestLexicalView view, int start, int actionStart, int from, int end,
                                   Operand destination, boolean creation) {
        String remainder = view.masked().substring(from, end);
        String prefix = view.masked().substring(start, actionStart);
        int literalStart = skipWhitespace(view.request(), from, end);
        RequestLexicalView.Span literal = view.exactQuotedContentStartingAt(literalStart, end);
        if (literal == null || destination.resolved() && literal.start() == destination.source().start()) {
            int introducerStart = from;
            if (destination.resolved()) {
                var envelope = view.exactQuotedEnvelope(destination.source().start(), destination.source().end());
                introducerStart = envelope == null ? destination.source().end() : envelope.end();
            } else {
                Matcher file = FILE_OBJECT_HEAD.matcher(view.masked()).region(from, end);
                if (file.lookingAt()) introducerStart = file.end();
            }
            Matcher introducer = LITERAL_INTRODUCER.matcher(view.request()).region(introducerStart, end);
            literal = null;
            if (introducer.lookingAt() && view.isUnquotedRange(introducer.start(), introducer.end())) {
                literalStart = introducer.end();
                literal = view.exactQuotedContentStartingAt(literalStart, end);
                // Code fences retain their existing decoder, including its language header handling.
                if (literal != null && view.request().startsWith("```", literalStart)) {
                    return new Operand(Binding.RESOLVED, view.request().substring(start, end), new SourceSpan(start, end));
                }
                if (literal == null && RequestQuotedContent.startsWithDelimiter(view.request().substring(literalStart, end))) {
                    return new Operand(Binding.MISSING, "", new SourceSpan(from, end));
                }
            }
        }
        if (literal != null && (!destination.resolved() || literal.start() != destination.source().start())) {
            var envelope = view.exactQuotedEnvelope(literal.start(), literal.end());
            String tail = view.masked().substring(envelope.end(), end);
            String rawTail = view.request().substring(envelope.end(), end).replaceFirst("^[\\s,;]*", "");
            if (RequestQuotedContent.startsWithDelimiter(rawTail)
                    || tail.matches("(?isu)^\\s*[,;]?\\s*(?:or|and(?:/or)?)\\b.*")) {
                return new Operand(Binding.AMBIGUOUS, "", new SourceSpan(from, end));
            }
            if (!view.substring(literal).isBlank()) {
                return new Operand(Binding.RESOLVED, view.substring(literal), new SourceSpan(literal.start(), literal.end()));
            }
        }
        // Generation constraints stay bound to the complete source clause, including coordination.
        if (CONTENT_DESCRIPTION.matcher(remainder).find()
                || FILE_DESCRIPTION.matcher(prefix).find() && CONTENT_DESCRIPTION.matcher(prefix).find()) {
            return new Operand(Binding.RESOLVED, view.request().substring(start, end), new SourceSpan(start, end));
        }
        // Creation supplies an artifact objective even without supplied bytes.
        // Its complete unquoted directive is a specification, never a literal payload.
        if (creation && (destination.resolved() || FILE_DESCRIPTION.matcher(remainder).find())
                && !CONTENT_PRONOUN.matcher(remainder).find()) {
            return new Operand(Binding.RESOLVED, view.request().substring(start, end), new SourceSpan(start, end));
        }
        if (!CONTENT_PRONOUN.matcher(remainder).find()) {
            int contentEnd = destination.resolved() ? destination.source().start() : end;
            String description = view.masked().substring(from, contentEnd)
                    .replaceFirst("(?iu)\\s*(?:named|called|as|to|into|in)\\s*[`'\"]?\\s*$", "")
                    .replaceAll("(?iu)\\b(?:(?:a|an|the|new|text|txt)\\s+)*file\\b", "")
                    .replaceAll("[\\s.!?,:;`'\"]", "");
            if (!description.isBlank() && !description.matches("(?iu)(?:a|an|the|there|here|it|this|that)")) {
                return new Operand(Binding.RESOLVED, view.request().substring(from, contentEnd), new SourceSpan(from, contentEnd));
            }
        }
        return new Operand(Binding.MISSING, "", new SourceSpan(from, end));
    }

    private static int skipWhitespace(String request, int start, int end) {
        while (start < end && Character.isWhitespace(request.charAt(start))) start++;
        return start;
    }
}
