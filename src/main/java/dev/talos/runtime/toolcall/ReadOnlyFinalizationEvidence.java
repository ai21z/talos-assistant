package dev.talos.runtime.toolcall;

import dev.talos.core.util.Sanitize;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.ReadOnlyAnswerProposal;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderResponseCapture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Selected source observations are retained without granting continuation or execution authority. */
public final class ReadOnlyFinalizationEvidence {
    public static final String SOURCE_PROJECTION_VERSION = "numbered-read-lf-v1";

    public enum Reason {
        EMPTY, COVERAGE_UNAVAILABLE, SOURCE_FORMAT_UNAVAILABLE, REQUEST_ASSOCIATION_UNAVAILABLE, BOUND
    }

    /** Coordinates belong to normalized observed text, never to original file bytes. */
    public record Source(String path, String text, String originId, long ordinal,
                         String bodyHash, String projectionVersion) {
        public Source {
            Objects.requireNonNull(path);
            Objects.requireNonNull(text);
            Objects.requireNonNull(originId);
            Objects.requireNonNull(bodyHash);
            Objects.requireNonNull(projectionVersion);
        }

        public String observationId() { return originId + ":" + ordinal; }

        @Override public String toString() {
            return "Source[observationId=" + observationId() + ", textChars=" + text.length() + "]";
        }
    }

    private static final ReadOnlyFinalizationEvidence EMPTY = new ReadOnlyFinalizationEvidence(
            null, null, "", 0, "", "", "", List.of(), Reason.EMPTY,
            null, ProviderResponseCapture.Receipt.none(), false, null);

    private final Path workspace;
    private final TaskContract contract;
    private final String traceId;
    private final int turn;
    private final String selectionId;
    private final String candidate;
    private final String candidateHash;
    private final List<Source> sources;
    private final Reason reason;
    private final ProviderResponseCapture.Scope captureScope;
    private final ProviderResponseCapture.Receipt receipt;
    private final boolean proposalSelected;
    private final ReadOnlyRequestMeaning requestMeaning;

    private ReadOnlyFinalizationEvidence(Path workspace, TaskContract contract, String traceId, int turn,
                                         String selectionId, String candidate, String candidateHash,
                                         List<Source> sources, Reason reason,
                                         ProviderResponseCapture.Scope captureScope,
                                         ProviderResponseCapture.Receipt receipt, boolean proposalSelected,
                                         ReadOnlyRequestMeaning requestMeaning) {
        if (contract != null && (requestMeaning == null
                || !contract.originalUserRequest().equals(requestMeaning.request())))
            throw new IllegalArgumentException("Request meaning must bind the retained contract request.");
        this.workspace = workspace;
        this.contract = contract;
        this.traceId = traceId;
        this.turn = turn;
        this.selectionId = selectionId;
        this.candidate = candidate;
        this.candidateHash = candidateHash;
        this.sources = List.copyOf(sources);
        this.reason = reason;
        this.captureScope = captureScope;
        this.receipt = receipt;
        this.proposalSelected = proposalSelected;
        this.requestMeaning = requestMeaning;
    }

    public static ReadOnlyFinalizationEvidence empty() { return EMPTY; }
    public Reason reason() { return reason; }
    public List<Source> sources() { return sources; }
    public String selectionId() { return selectionId; }
    public String generationId() { return available() ? receipt.generationId() : ""; }
    public String candidateHash() { return candidateHash; }
    public boolean proposalSelected() { return proposalSelected; }
    public ReadOnlyRequestMeaning requestMeaning() { return requestMeaning; }

    /** The route is retained when its candidate or association is invalidated. */
    public ReadOnlyFinalizationEvidence invalidate() {
        if (!proposalSelected) return EMPTY;
        return new ReadOnlyFinalizationEvidence(workspace, contract, traceId, turn, selectionId,
                "", "", List.of(), Reason.REQUEST_ASSOCIATION_UNAVAILABLE, captureScope,
                ProviderResponseCapture.Receipt.none(), true, requestMeaning);
    }

    public boolean available() {
        return reason == Reason.BOUND && receipt.providerTerminalEligible()
                && receipt.readInputBinding().bound() && requestMeaning != null;
    }

    /** Only exact selected text in the owning active turn can use this source association. */
    public boolean matches(String answer, Path currentWorkspace, TaskContract currentContract) {
        return available() && candidate.equals(answer) && workspace != null
                && receipt.belongsToCurrentScope()
                && workspace.equals(normalize(currentWorkspace)) && contract.equals(currentContract)
                && requestMeaning.request().equals(currentContract.originalUserRequest())
                && LocalTurnTraceCapture.isActive()
                && captureScope == ProviderResponseCapture.currentScope()
                && traceId.equals(LocalTurnTraceCapture.currentTraceId())
                && turn == LocalTurnTraceCapture.currentTurnNumber();
    }

    /** The executed ledger and formatted carrier are frozen before any provider request. */
    static Frozen freeze(LoopState state, ReadOnlyEvidenceContext.Selection selection) {
        TaskContract contract = TurnTaskContractCapture.get();
        if (state == null || selection == null || !selection.complete() || contract == null
                || ProviderResponseCapture.currentScope() == null
                || !LocalTurnTraceCapture.isActive() || LocalTurnTraceCapture.currentTraceId().isBlank())
            return Frozen.unavailable(Reason.COVERAGE_UNAVAILABLE);
        Path workspace = normalize(state.workspace);
        if (workspace == null) return Frozen.unavailable(Reason.COVERAGE_UNAVAILABLE);
        List<FrozenRead> reads = new ArrayList<>();
        try {
            for (var carrier : selection.carriers().stream()
                    .sorted(Comparator.comparingInt(ReadOnlyEvidenceContext.Carrier::index)).toList()) {
                ReadEvidence observation = carrier.evidence();
                var bound = state.readEvidence.toolResult(observation);
                if (bound == null || bound.body() == null || !bound.message().equals(carrier.message())
                        || observation.originId().isBlank() || observation.originOrdinal() < 1
                        || observation.ranged() || observation.truncated() || observation.redacted())
                    return Frozen.unavailable(Reason.COVERAGE_UNAVAILABLE);
                var body = bound.body();
                String content = bound.message().content();
                if (!body.observedBody().equals(observation.body()) || body.start() < 0
                        || body.end() > content.length() || body.end() < body.start()
                        || !content.substring(body.start(), body.end()).equals(body.displayedBody()))
                    return Frozen.unavailable(Reason.SOURCE_FORMAT_UNAVAILABLE);
                String projectedBody = Sanitize.sanitizeMessageContent(body.displayedBody());
                String prepared = Sanitize.sanitizeMessageContent(content);
                String reconstructed = Sanitize.sanitizeMessageContent(content.substring(0, body.start()))
                        + projectedBody + Sanitize.sanitizeMessageContent(content.substring(body.end()));
                if (!prepared.equals(reconstructed))
                    return Frozen.unavailable(Reason.SOURCE_FORMAT_UNAVAILABLE);
                String text = decodeNumberedBody(projectedBody);
                if (text == null) return Frozen.unavailable(Reason.SOURCE_FORMAT_UNAVAILABLE);
                Path executed = workspace.resolve(observation.effectivePath()).normalize();
                if (!executed.startsWith(workspace) || executed.equals(workspace))
                    return Frozen.unavailable(Reason.COVERAGE_UNAVAILABLE);
                String path = workspace.relativize(executed).toString().replace('\\', '/');
                Source source = new Source(path, text, observation.originId(), observation.originOrdinal(),
                        observation.contentHash(), SOURCE_PROJECTION_VERSION);
                reads.add(new FrozenRead(source, carrier.message().toolCallId(), prepared, carrier.call()));
            }
            if (reads.isEmpty() || reads.size() > ReadOnlyEvidenceContext.MAX_TARGETS)
                return Frozen.unavailable(Reason.COVERAGE_UNAVAILABLE);
            return new Frozen(workspace, contract, LocalTurnTraceCapture.currentTraceId(),
                    LocalTurnTraceCapture.currentTurnNumber(), UUID.randomUUID().toString(), reads, Reason.BOUND,
                    ProviderResponseCapture.currentScope());
        } catch (RuntimeException invalid) {
            return Frozen.unavailable(Reason.SOURCE_FORMAT_UNAVAILABLE);
        }
    }

    /** Only the exact complete numbered ordinary-text representation is accepted. */
    static String decodeNumberedBody(String body) {
        if (body == null || body.isEmpty() || body.length() > ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS
                || !body.endsWith("\n") || body.indexOf('\r') >= 0
                || body.contains("... (") || body.contains("output truncated")
                || RedactedReadWritebackGuard.containsRedactionSentinel(body)) return null;
        StringBuilder source = new StringBuilder(body.length());
        int offset = 0;
        int number = 1;
        while (offset < body.length()) {
            int end = body.indexOf('\n', offset);
            if (end < 0) return null;
            String prefix = number++ + " | ";
            if (!body.startsWith(prefix, offset) || end < offset + prefix.length()) return null;
            source.append(body, offset + prefix.length(), end).append('\n');
            offset = end + 1;
        }
        return source.toString();
    }

    private record FrozenRead(Source source, String toolCallId, String preparedContent,
                              ChatMessage.NativeToolCall call) {}

    static final class Frozen {
        private final Path workspace;
        private final TaskContract contract;
        private final String traceId;
        private final int turn;
        private final String selectionId;
        private final List<FrozenRead> reads;
        private final Reason reason;
        private final ProviderResponseCapture.Scope captureScope;
        private final boolean proposalSelected;
        private final ReadOnlyRequestMeaning requestMeaning;

        private Frozen(Path workspace, TaskContract contract, String traceId, int turn,
                       String selectionId, List<FrozenRead> reads, Reason reason,
                       ProviderResponseCapture.Scope captureScope) {
            this.workspace = workspace;
            this.contract = contract;
            this.traceId = traceId;
            this.turn = turn;
            this.selectionId = selectionId;
            this.reads = List.copyOf(reads);
            this.reason = reason;
            this.captureScope = captureScope;
            this.requestMeaning = reason == Reason.BOUND
                    ? ReadOnlyRequestMeaning.compile(contract.originalUserRequest()) : null;
            this.proposalSelected = reason == Reason.BOUND && ReadOnlyAnswerProposal.eligible(sources());
        }

        List<Source> sources() { return reads.stream().map(FrozenRead::source).toList(); }
        boolean proposalSelected() { return proposalSelected; }
        ReadOnlyRequestMeaning requestMeaning() { return requestMeaning; }

        ReadOnlyFinalizationEvidence pending() {
            return new ReadOnlyFinalizationEvidence(workspace, contract, traceId, turn, selectionId,
                    "", "", List.of(), Reason.REQUEST_ASSOCIATION_UNAVAILABLE, captureScope,
                    ProviderResponseCapture.Receipt.none(), proposalSelected, requestMeaning);
        }

        private static Frozen unavailable(Reason reason) {
            return new Frozen(null, null, "", 0, "", List.of(), reason, null);
        }

        ReadOnlyFinalizationEvidence bind(String providerText, ProviderResponseCapture.Receipt selected) {
            String returnedText = Objects.toString(providerText, "");
            Reason bindingReason = reason;
            if (bindingReason == Reason.BOUND) {
                var binding = selected == null ? null : selected.readInputBinding();
                boolean matched = selected != null && selected.providerTerminalEligible()
                        && selected.belongsToCurrentScope()
                        && validUnicode(returnedText)
                        && ("sha256:" + hash(returnedText)).equals(selected.assistantHash())
                        && LocalTurnTraceCapture.isActive()
                        && captureScope == ProviderResponseCapture.currentScope()
                        && traceId.equals(LocalTurnTraceCapture.currentTraceId())
                        && turn == LocalTurnTraceCapture.currentTurnNumber()
                        && contract.equals(TurnTaskContractCapture.get())
                        && requestMeaning != null
                        && requestMeaning.request().equals(contract.originalUserRequest())
                        && selected.completion() == ProviderResponseCapture.Completion.PROVIDER_EOS
                        && selected.toolAuthorityMode() == ProviderResponseCapture.ToolAuthorityMode.NONE
                        && binding != null && binding.bound()
                        && ProviderReadInputBinding.SANITIZER_VERSION.equals(binding.sanitizerVersion())
                        && binding.preparedToolResults().size() == reads.size();
                if (matched) {
                    for (int i = 0; i < reads.size(); i++) {
                        var read = reads.get(i);
                        var admitted = binding.preparedToolResults().get(i);
                        if (!read.toolCallId().equals(admitted.toolCallId())
                                || !read.preparedContent().equals(admitted.content())) {
                            matched = false;
                            break;
                        }
                    }
                }
                if (!matched) bindingReason = Reason.REQUEST_ASSOCIATION_UNAVAILABLE;
            }
            String candidate = returnedText.strip();
            return new ReadOnlyFinalizationEvidence(workspace, contract, traceId, turn, selectionId,
                    candidate, hash(candidate), reads.stream().map(FrozenRead::source).toList(), bindingReason,
                    captureScope, bindingReason == Reason.BOUND ? selected : ProviderResponseCapture.Receipt.none(),
                    proposalSelected, requestMeaning);
        }
    }

    private static Path normalize(Path path) {
        try { return path == null ? null : path.toAbsolutePath().normalize(); }
        catch (RuntimeException invalid) { return null; }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static boolean validUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }
}
